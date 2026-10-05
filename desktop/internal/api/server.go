// Package api implements the HydroLink HTTP API. It is byte-compatible
// with the Android server: same routes (with /api and /api-less aliases),
// same JSON shapes, same trust/auth semantics and CORS behavior.
package api

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/store"
	"hydrolink-desktop/internal/system"
)

const ()

// Version 是产品版本号。默认值与 desktop/VERSION 保持一致，打包/发布脚本用
// `-ldflags "-X hydrolink-desktop/internal/api.Version=<版本>"` 注入，保证
// 二进制里报告的版本与包版本永远一致。
var Version = "1.2.1"

// defaultPort 与 cmd/hydrolink 的兜底一致（配置文件里没写 last_port 时用它）。
const defaultPort = 9000

// LogEntry mirrors Android's LogEntry.
type LogEntry struct {
	Text  string `json:"text"`
	Level string `json:"level"`
}

// Server is the HydroLink HTTP server.
type Server struct {
	store *store.Store

	logMu   sync.Mutex
	logs    []LogEntry
	maxLogs int

	srvMu   sync.Mutex
	httpSrv *http.Server

	// logOut 是日志的终端镜像目标（stdout + 日志文件；见 internal/applog）。
	logOut io.Writer

	// hydro is the HydroKit plugin API dispatcher (optional; mounted under
	// /api/hydro/...). This keeps the route surface fixed as plugins grow.
	hydro http.Handler

	// worker is the HydroKit plugin-worker channel (optional; mounted under
	// /api/hydro/worker/...). Handled before the hydro dispatcher so worker
	// endpoints never collide with plugin routes.
	worker http.Handler

	// workerBridge is the WorkerBridge used by JS plugins (for wake events).
	workerBridge *hydrokit.WorkerBridge

	// pairCallback, when set, is invoked for every NEW pending pairing
	// request (used by the desktop GUI for popups/notifications).
	pairCallback func(deviceID, deviceName string)

	// loadPlugin/unloadPlugin are installed by the app wiring: hot
	// register/unregister a plugin package in the HydroKit runtime (called by
	// the GUI right after install/uninstall, so the watch sees the change
	// without a server restart).
	// port/portMu：实际监听端口（设置页要区分「当前端口」和「下次启动用的端口」）
	portMu sync.Mutex
	port   int

	// reloadPlugins 由 app 层注入：重扫插件目录并热注册（POST /api/hydro/reload）
	reloadPlugins func() ([]string, error)
	loadPlugin    func(id string) error
	unloadPlugin  func(id string) error
}

// New creates a Server backed by the given store.
func New(s *store.Store) *Server {
	return &Server{store: s, maxLogs: 200}
}

// SetPairCallback registers a handler invoked on each new pairing request.
func (s *Server) SetPairCallback(fn func(deviceID, deviceName string)) {
	s.pairCallback = fn
}

// SetHydroHandler mounts the HydroKit plugin API dispatcher under
// /api/hydro/... (see the "hydro" field).
//
// Responses carry Cache-Control: no-store because plugin views change with
// host state (music tracks, CPU/RAM) and clients poll the same URL.
func (s *Server) SetHydroHandler(h http.Handler) {
	s.hydro = h
	// 插件条目的图标可能是包内相对路径（manifest 里写 assets/icon.png），
	// list 时要解析成手表能直接取的绝对地址（局域网 IP + 本端口）。
	hydrokit.BaseURLFor = func(r *http.Request) string {
		return "http://" + apiBaseHost(r)
	}
	// 桌面 UI 靠 list 里的 web 标记决定是否显示「界面」按钮（有 web/index.html 才显示）。
	hydrokit.HasWebFor = func(pkg string) bool {
		if pkg == "" {
			return false
		}
		info, err := os.Stat(filepath.Join(s.store.PluginsDir(), pkg, "web", "index.html"))
		return err == nil && !info.IsDir()
	}
}

// SetWorkerHandler mounts the plugin-worker channel under
// /api/hydro/worker/... (see the "worker" field).
func (s *Server) SetWorkerHandler(h http.Handler) {
	s.worker = h
}

// SetPluginHooks installs the hot-load callbacks used after install/uninstall.
func (s *Server) SetPluginHooks(load, unload func(id string) error) {
	s.loadPlugin = load
	s.unloadPlugin = unload
}

// LoadPlugin hot-registers a plugin package in the runtime.
func (s *Server) LoadPlugin(id string) error {
	if s.loadPlugin == nil {
		return fmt.Errorf("plugin runtime not available")
	}
	return s.loadPlugin(id)
}

// UnloadPlugin hot-unregisters a plugin package from the runtime.
func (s *Server) UnloadPlugin(id string) error {
	if s.unloadPlugin == nil {
		return fmt.Errorf("plugin runtime not available")
	}
	return s.unloadPlugin(id)
}

// SetPluginReloader installs the full "re-scan the plugins directory" callback.
//
// 为什么需要它：插件**安装**在桌面端是由 UI 侧做的（Compose 端把 .hpk 解到数据目录，
// 与安卓端同构），Go 侧只负责运行；UI 装完调 POST /api/hydro/reload 让运行时重扫，
// 就不必重启服务。返回本次成功注册的插件 id。
func (s *Server) SetPluginReloader(reload func() ([]string, error)) {
	s.reloadPlugins = reload
}

// ReloadPlugins re-scans the plugin directory and hot-(re)registers everything.
// It is a no-op error when no reloader was installed (embedded/headless mode).
func (s *Server) ReloadPlugins() ([]string, error) {
	if s.reloadPlugins == nil {
		return nil, fmt.Errorf("plugin reloader not available")
	}
	return s.reloadPlugins()
}

// SetWorkerBridge records the WorkerBridge backing JS plugins (GUI uses it to
// hook wake events).
func (s *Server) SetWorkerBridge(b *hydrokit.WorkerBridge) {
	s.workerBridge = b
}

// Port returns the port the server is actually listening on (0 before Start).
func (s *Server) Port() int {
	s.portMu.Lock()
	defer s.portMu.Unlock()
	return s.port
}

// WorkerBridge returns the bridge backing JS plugins (nil when unset).
func (s *Server) WorkerBridge() *hydrokit.WorkerBridge {
	return s.workerBridge
}

// AddLog appends a log entry (kept for the desktop GUI) and mirrors it to
// the terminal.
func (s *Server) AddLog(message, level string) {
	ts := time.Now().Format("15:04:05")
	s.logMu.Lock()
	s.logs = append(s.logs, LogEntry{Text: "[" + ts + "] " + message, Level: level})
	if len(s.logs) > s.maxLogs {
		s.logs = s.logs[len(s.logs)-s.maxLogs:]
	}
	s.logMu.Unlock()
	// Mirror to the terminal (and the log file) so headless/GUI launches keep
	// a record — a Windows GUI build has no console at all.
	out := s.logOut
	if out == nil {
		out = os.Stdout
	}
	fmt.Fprintf(out, "[%s] [%s] %s\n", ts, level, message)
}

// SetLogWriter redirects the terminal mirror of AddLog (stdout by default).
func (s *Server) SetLogWriter(w io.Writer) {
	s.logMu.Lock()
	s.logOut = w
	s.logMu.Unlock()
}

// Logs returns a copy of the log buffer.
func (s *Server) Logs() []LogEntry {
	s.logMu.Lock()
	defer s.logMu.Unlock()
	out := make([]LogEntry, len(s.logs))
	copy(out, s.logs)
	return out
}

// Handler returns the root HTTP handler.
func (s *Server) Handler() http.Handler {
	return http.HandlerFunc(s.serve)
}

// Start begins serving on addr in a background goroutine. The server can be
// stopped with Stop (used by the GUI when the port setting changes).
func (s *Server) Start(addr string) error {
	if _, p, err := net.SplitHostPort(addr); err == nil {
		if n, err := strconv.Atoi(p); err == nil {
			s.portMu.Lock()
			s.port = n
			s.portMu.Unlock()
		}
	}
	s.srvMu.Lock()
	if s.httpSrv != nil {
		s.srvMu.Unlock()
		return nil
	}
	srv := &http.Server{
		Addr:         addr,
		Handler:      s.Handler(),
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 60 * time.Second,
	}
	s.httpSrv = srv
	s.srvMu.Unlock()

	ln, err := net.Listen("tcp", addr)
	if err != nil {
		s.srvMu.Lock()
		s.httpSrv = nil
		s.srvMu.Unlock()
		return err
	}
	go func() { _ = srv.Serve(ln) }()
	return nil
}

// Stop gracefully shuts the HTTP server down (no-op if not running).
// Started reports whether the HTTP listener is currently running.
func (s *Server) Started() bool {
	s.srvMu.Lock()
	defer s.srvMu.Unlock()
	return s.httpSrv != nil
}

// BindError explains a listen failure in user terms (the most common cause by
// far is "HydroLink is already running").
func BindError(port int, err error) error {
	return fmt.Errorf("端口 %d 无法监听（可能已有 HydroLink 实例在运行，或被其他程序占用）: %v", port, err)
}

func (s *Server) Stop() {
	s.srvMu.Lock()
	srv := s.httpSrv
	s.httpSrv = nil
	s.srvMu.Unlock()
	if srv != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	}
}

// ListenAndServe blocks serving on addr (headless mode).
func (s *Server) ListenAndServe(addr string) error {
	if err := s.Start(addr); err != nil {
		return err
	}
	select {}
}

func (s *Server) serve(w http.ResponseWriter, r *http.Request) {
	// CORS preflight
	if r.Method == http.MethodOptions {
		s.addCors(w)
		w.WriteHeader(http.StatusOK)
		return
	}
	defer s.addCors(w)

	path := r.URL.Path
	// Strip trailing slash (except root)
	if len(path) > 1 && strings.HasSuffix(path, "/") {
		path = strings.TrimSuffix(path, "/")
	}

	// Plugin WebUI assets are served from the plugin package's web/ dir.
	if strings.HasPrefix(path, "/api/hydro/webui/") && r.Method == http.MethodGet {
		s.handleWebUI(w, r)
		return
	}

	// Worker channel is internal to the plugin runtime and is handled before
	// the plugin dispatcher, so a plugin package named "worker" can never
	// shadow it.
	if s.worker != nil && strings.HasPrefix(path, "/api/hydro/worker/") {
		s.worker.ServeHTTP(w, r)
		return
	}

	// 插件目录重扫（UI 侧装完插件调用，让运行时热加载，不必重启服务）。
	// 放在 hydro 分发之前：/api/hydro/reload 不是 4 段路径，否则会被当成插件请求。
	if path == "/api/hydro/reload" {
		if r.Method != http.MethodPost {
			s.writeJSON(w, http.StatusMethodNotAllowed, map[string]any{
				"status": "error", "message": "reload requires POST",
			})
			return
		}
		ids, err := s.ReloadPlugins()
		if err != nil {
			s.AddLog("插件重载失败: "+err.Error(), "error")
			s.writeJSON(w, http.StatusServiceUnavailable, map[string]any{
				"status": "error", "message": err.Error(),
			})
			return
		}
		s.AddLog(fmt.Sprintf("插件已重载: %d 个", len(ids)), "success")
		s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "plugins": ids})
		return
	}

	// Bare /api/hydro must not be cached by any intermediate (watch WebView).
	if strings.HasPrefix(path, "/api/hydro") {
		w.Header().Set("Cache-Control", "no-store")
	}

	// Delegate HydroKit plugin API to its own dispatcher.
	if s.hydro != nil && (strings.HasPrefix(path, "/api/hydro/") || path == "/api/hydro") {
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		s.hydro.ServeHTTP(rec, r)
		s.AddLog(fmt.Sprintf("hydro %s %s -> %d", r.Method, strings.TrimPrefix(r.URL.RequestURI(), "/api/hydro"), rec.status), "info")
		return
	}

	switch {
	// ---- Public endpoints ----
	case (path == "/api/ping" || path == "/ping") && r.Method == http.MethodGet:
		s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})

	case (path == "/api/version" || path == "/version") && r.Method == http.MethodGet:
		s.writeJSON(w, http.StatusOK, map[string]any{
			// app 是给局域网探测用的指纹（见 probe.go），取代原来的 api_level
			"status": "ok", "app": "hydrolink", "version": Version,
		})

	case (path == "/api/pid" || path == "/pid") && r.Method == http.MethodGet:
		s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "pid": os.Getpid()})

	case (path == "/api/pair" || path == "/pair") && r.Method == http.MethodPost:
		s.handlePair(w, r)

	case strings.HasPrefix(path, "/api/check/") && r.Method == http.MethodGet:
		s.handleCheck(w, strings.TrimPrefix(path, "/api/check/"))
	case strings.HasPrefix(path, "/check/") && r.Method == http.MethodGet:
		s.handleCheck(w, strings.TrimPrefix(path, "/check/"))

	case (path == "/api/auth" || path == "/auth") && r.Method == http.MethodGet:
		s.handleAuth(w, r)

	case (path == "/api/pending_pair" || path == "/pending_pair") && r.Method == http.MethodGet:
		pairs, err := s.store.LoadPendingPairs()
		if err != nil {
			s.writeJSON(w, http.StatusInternalServerError, map[string]any{"status": "error", "message": "Internal error"})
			return
		}
		s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "pairs": pairs})

	case (path == "/api/pair/respond" || path == "/pair/respond") && r.Method == http.MethodPost:
		s.handleRespondPair(w, r)

	// ---- Trusted endpoints ----
	case (path == "/api/status" || path == "/status") && r.Method == http.MethodGet:
		s.withTrusted(w, r, func() { s.handleStatus(w) })

	case (path == "/api/lock" || path == "/lock") && r.Method == http.MethodPost:
		s.withTrusted(w, r, func() { s.handleLock(w) })

	case (path == "/api/performance" || path == "/performance") && r.Method == http.MethodGet:
		s.withTrusted(w, r, func() { s.handlePerformance(w) })

	case (path == "/api/music/art" || path == "/music/art") && r.Method == http.MethodGet:
		s.handleMusicArt(w, r)

	case (path == "/api/music" || path == "/music") && r.Method == http.MethodGet:
		s.withTrusted(w, r, func() { s.handleGetMusic(w) })

	case (path == "/api/music/control" || path == "/music/control") && r.Method == http.MethodPost:
		s.withTrusted(w, r, func() { s.handleMusicControl(w, r) })

	// ---- 桌面 UI 侧需要的管理端点（Compose 端自实现业务，不走 Wails 绑定）----
	case path == "/api/plugins/install" && r.Method == http.MethodPost:
		s.handleInstallPlugin(w, r)

	case strings.HasPrefix(path, "/api/plugins/") && r.Method == http.MethodDelete:
		s.handleUninstallPlugin(w, strings.TrimPrefix(path, "/api/plugins/"))

	case path == "/api/logs" && r.Method == http.MethodGet:
		s.handleGetLogs(w)

	case path == "/api/devices" && r.Method == http.MethodGet:
		s.handleGetDevices(w)

	case strings.HasPrefix(path, "/api/devices/") && r.Method == http.MethodDelete:
		s.handleDeleteDevice(w, strings.TrimPrefix(path, "/api/devices/"))

	case path == "/api/settings" && r.Method == http.MethodGet:
		s.handleGetSettings(w)

	case path == "/api/settings" && r.Method == http.MethodPut:
		s.handlePutSettings(w, r)

	case path == "/api/commands" && r.Method == http.MethodPost:
		s.handleCreateCommand(w, r)

	case matchCommandID(path) && r.Method == http.MethodPut:
		s.handleUpdateCommand(w, r, commandID(path))

	case matchCommandID(path) && r.Method == http.MethodDelete:
		s.handleDeleteCommand(w, commandID(path))

	case (path == "/api/commands" || path == "/commands") && r.Method == http.MethodGet:
		s.withTrusted(w, r, func() { s.handleGetCommands(w) })

	case matchRunCommand(path) && r.Method == http.MethodPost:
		id := runCommandID(path)
		s.withTrusted(w, r, func() { s.handleRunCommand(w, id) })

	// ---- Root / health check ----
	case path == "/" || path == "":
		s.writeJSON(w, http.StatusOK, map[string]any{
			"status": "ok", "service": "HydroLinkServer", "version": Version,
		})

	default:
		s.writeJSON(w, http.StatusNotFound, map[string]any{"status": "error", "message": "Not found"})
	}
}

func matchRunCommand(path string) bool {
	if strings.HasPrefix(path, "/api/commands/") && strings.HasSuffix(path, "/run") {
		_, err := strconv.Atoi(strings.TrimSuffix(strings.TrimPrefix(path, "/api/commands/"), "/run"))
		return err == nil
	}
	if strings.HasPrefix(path, "/commands/") && strings.HasSuffix(path, "/run") {
		_, err := strconv.Atoi(strings.TrimSuffix(strings.TrimPrefix(path, "/commands/"), "/run"))
		return err == nil
	}
	return false
}

func runCommandID(path string) int {
	var s string
	if strings.HasPrefix(path, "/api/commands/") {
		s = strings.TrimSuffix(strings.TrimPrefix(path, "/api/commands/"), "/run")
	} else {
		s = strings.TrimSuffix(strings.TrimPrefix(path, "/commands/"), "/run")
	}
	id, _ := strconv.Atoi(s)
	return id
}

// ---- Handlers ----

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DeviceID   string `json:"device_id"`
		DeviceName string `json:"device_name"`
	}
	if !parseBody(r, &body) {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Invalid JSON"})
		return
	}
	body.DeviceID = strings.TrimSpace(body.DeviceID)
	if body.DeviceID == "" || len(body.DeviceID) > 100 {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Invalid device_id"})
		return
	}
	body.DeviceName = strings.TrimSpace(body.DeviceName)
	if len(body.DeviceName) > 50 {
		body.DeviceName = body.DeviceName[:50]
	}

	cfg, _ := s.store.LoadConfig()

	if s.store.IsTrusted(body.DeviceID) {
		if body.DeviceName != "" {
			_ = s.store.AddTrustedDevice(body.DeviceID, body.DeviceName)
		}
		s.AddLog("设备 "+shortID(body.DeviceID)+" 已信任", "success")
		s.writeJSON(w, http.StatusOK, map[string]any{
			"status": "trusted", "message": "Device already trusted", "encryption_key": cfg.EncryptionKey,
		})
		return
	}

	// A fresh pairing attempt gives the user another chance: clear any prior
	// rejection so this request is prompted again instead of auto-rejected.
	_ = s.store.RemoveRejected(body.DeviceID)

	if cfg.AcceptAll {
		_ = s.store.AddTrustedDevice(body.DeviceID, body.DeviceName)
		s.AddLog("自动信任设备: "+shortID(body.DeviceID)+" ("+body.DeviceName+")", "success")
		s.writeJSON(w, http.StatusOK, map[string]any{
			"status": "trusted", "message": "Auto accepted", "encryption_key": cfg.EncryptionKey,
		})
		return
	}

	s.AddLog("配对请求: "+shortID(body.DeviceID), "warning")
	added, _ := s.store.AddPendingPair(body.DeviceID, body.DeviceName)
	if added && s.pairCallback != nil {
		s.pairCallback(body.DeviceID, body.DeviceName)
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"status": "pending", "message": "Waiting for user confirmation"})
}

func (s *Server) handleCheck(w http.ResponseWriter, deviceID string) {
	if deviceID == "" || len(deviceID) > 100 {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error"})
		return
	}
	if s.store.IsRejected(deviceID) {
		s.writeJSON(w, http.StatusOK, map[string]any{"status": "rejected"})
		return
	}
	if s.store.IsTrusted(deviceID) {
		cfg, _ := s.store.LoadConfig()
		s.writeJSON(w, http.StatusOK, map[string]any{"status": "trusted", "encryption_key": cfg.EncryptionKey})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"status": "pending"})
}

func (s *Server) handleAuth(w http.ResponseWriter, r *http.Request) {
	deviceID := strings.TrimSpace(r.Header.Get("X-Device-ID"))
	if deviceID == "" || len(deviceID) > 100 {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Missing device ID"})
		return
	}
	status := "unauthorized"
	if s.store.IsTrusted(deviceID) {
		status = "authorized"
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"status": status})
}

func (s *Server) handleRespondPair(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DeviceID   string `json:"device_id"`
		Action     string `json:"action"`
		DeviceName string `json:"device_name"`
	}
	if !parseBody(r, &body) {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Invalid JSON"})
		return
	}
	body.DeviceID = strings.TrimSpace(body.DeviceID)
	body.Action = strings.TrimSpace(body.Action)
	if body.DeviceID == "" || (body.Action != "accept" && body.Action != "reject") {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Invalid params"})
		return
	}
	if len(body.DeviceName) > 50 {
		body.DeviceName = body.DeviceName[:50]
	}
	deviceName := strings.TrimSpace(body.DeviceName)
	if deviceName == "" {
		pending, _ := s.store.LoadPendingPairs()
		for _, p := range pending {
			if p.DeviceID == body.DeviceID && strings.TrimSpace(p.DeviceName) != "" {
				deviceName = strings.TrimSpace(p.DeviceName)
				break
			}
		}
	}
	_ = s.store.RemovePendingPair(body.DeviceID)
	if body.Action == "accept" {
		_ = s.store.AddTrustedDevice(body.DeviceID, deviceName)
		s.AddLog("已信任设备: "+shortID(body.DeviceID)+" ("+deviceName+")", "success")
	} else {
		_ = s.store.AddRejected(body.DeviceID)
		s.AddLog("拒绝设备: "+shortID(body.DeviceID), "error")
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

func (s *Server) handleStatus(w http.ResponseWriter) {
	devices, _ := s.store.LoadTrustedDevices()
	bat := system.GetBattery()
	s.writeJSON(w, http.StatusOK, map[string]any{
		"status":        "running",
		"trusted_count": len(devices),
		"system": map[string]any{
			"hostname": system.Hostname(),
			"version":  system.Version(),
			// 局域网 IP：手表/手机要连的是这个，只给 hostname 的话 UI 上没法显示地址
			"ip": LocalIP(),
		},
		"battery": map[string]any{
			"has_battery": bat.HasBattery,
			"percent":     bat.Percent,
			"charging":    bat.Charging,
		},
	})
}

func (s *Server) handleLock(w http.ResponseWriter) {
	if system.LockScreen() {
		s.AddLog("设备已锁屏", "success")
		s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "message": "Screen locked"})
	} else {
		s.AddLog("锁屏失败", "error")
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{"status": "error", "message": "Lock failed"})
	}
}

func (s *Server) handlePerformance(w http.ResponseWriter) {
	p, err := system.GetPerf()
	if err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{"status": "error", "message": "Failed to get performance"})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"status":      "ok",
		"cpu":         p.CPU,
		"mem_percent": p.MemPercent,
		"mem_used":    p.MemUsedMB,
		"mem_total":   p.MemTotalMB,
		"temp":        nil,
	})
}

func (s *Server) handleGetMusic(w http.ResponseWriter) {
	info := system.GetMusic()
	// 读取失败（如 Windows 上 SMTC 不可用）不能静默：记一条日志便于排查
	// "手表看不到音乐"。成功路径不产生噪音。
	if msg := system.MusicDiagnostics(); msg != "" {
		s.AddLog("music: "+msg, "warning")
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"status":    "ok",
		"has_media": info.HasMedia,
		"title":     info.Title,
		"artist":    info.Artist,
		"playing":   info.Playing,
	})
}

func (s *Server) handleMusicControl(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Action string `json:"action"`
	}
	if !parseBody(r, &body) {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Invalid JSON"})
		return
	}
	action := strings.TrimSpace(body.Action)
	switch action {
	case system.ActionPlay, system.ActionPause, system.ActionPlayPause, system.ActionNext, system.ActionPrevious:
	default:
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Invalid action"})
		return
	}
	if system.ControlMusic(action) {
		s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
	} else {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{"status": "error", "message": "No active media session"})
	}
}

func (s *Server) handleGetCommands(w http.ResponseWriter) {
	commands, _ := s.store.LoadCommands()
	result := make([]map[string]any, 0, len(commands))
	for i, c := range commands {
		result = append(result, map[string]any{
			"id":      i,
			"name":    truncate(c.Name, 50),
			"command": truncate(c.Command, 200),
		})
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "commands": result})
}

func (s *Server) handleRunCommand(w http.ResponseWriter, cmdID int) {
	commands, _ := s.store.LoadCommands()
	if cmdID < 0 || cmdID >= len(commands) {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Invalid command ID"})
		return
	}
	cmdStr := strings.TrimSpace(commands[cmdID].Command)
	if cmdStr == "" {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Empty command"})
		return
	}
	s.AddLog("执行命令: "+commands[cmdID].Name, "warning")
	res := system.RunCommand(cmdStr)
	if res.Success {
		s.AddLog(fmt.Sprintf("命令完成: 返回码 %d", res.ExitCode), "success")
		s.writeJSON(w, http.StatusOK, map[string]any{
			"status": "ok", "returncode": res.ExitCode, "output": truncate(res.Output, 1000),
		})
	} else {
		s.AddLog("命令失败: "+truncate(res.Output, 200), "error")
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": "Command failed: " + truncate(res.Output, 500),
		})
	}
}

// ---- Trust helper ----

func (s *Server) withTrusted(w http.ResponseWriter, r *http.Request, block func()) {
	cfg, _ := s.store.LoadConfig()
	if cfg.AcceptAll {
		block()
		return
	}
	// 本机进程（桌面 Compose 端就住在这个进程旁边）直接放行：loopback 请求只可能来自
	// 跑着服务的那台机器。这道门禁是给 LAN 上的手表/手机准备的——它们不能没有 device id
	// 就读到状态和命令。判定只看 TCP 对端地址，不看任何请求头，所以伪造头也绕不过去。
	if isLoopbackAddr(r.RemoteAddr) {
		block()
		return
	}
	deviceID := strings.TrimSpace(r.Header.Get("X-Device-ID"))
	if deviceID == "" || len(deviceID) > 100 {
		s.writeJSON(w, http.StatusUnauthorized, map[string]any{"status": "error", "message": "Missing device ID"})
		return
	}
	if !s.store.IsTrusted(deviceID) {
		s.writeJSON(w, http.StatusForbidden, map[string]any{"status": "error", "message": "Device not trusted"})
		return
	}
	block()
}

// isLoopbackAddr 判断对端是不是本机（127.0.0.0/8、::1、空地址）。
func isLoopbackAddr(remoteAddr string) bool {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	host = strings.TrimSpace(host)
	if host == "" {
		// httptest 之类没有真实对端：按本机处理（测试与本地直连都走这条）
		return true
	}
	if host == "::1" || host == "[::1]" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// ---- Helpers ----

// statusRecorder captures the status code a handler writes so callers can log it.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func (s *Server) addCors(w http.ResponseWriter) {
	h := w.Header()
	h.Set("Access-Control-Allow-Origin", "*")
	h.Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
	h.Set("Access-Control-Allow-Headers", "Content-Type, X-Device-ID, Authorization")
	h.Set("Access-Control-Max-Age", "86400")
	h.Set("Connection", "close")
}

func (s *Server) writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("write json: %v", err)
	}
}

func parseBody(r *http.Request, out any) bool {
	defer r.Body.Close()
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(out); err != nil {
		return false
	}
	return true
}

func shortID(id string) string {
	if len(id) <= 8 {
		return id
	}
	return id[:8] + "..."
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// LocalIP returns the LAN address the watch should use (see localip.go).

// ---- 桌面 UI 侧的管理端点 ----

// handleInstallPlugin 安装一个本地 .hpk 并热注册。
//
// 职责划分：UI 侧负责下载与 sha256 校验（它才有市场索引），Go 侧负责解包安装与热注册
// ——插件目录格式只有 store 这一处实现，不让两端各写一份解包逻辑。
func (s *Server) handleInstallPlugin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Path string `json:"path"`
	}
	if !parseBody(r, &body) {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{
			"status": "error", "message": "Invalid JSON",
		})
		return
	}
	path := strings.TrimSpace(body.Path)
	if path == "" {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{
			"status": "error", "message": "缺少插件包路径",
		})
		return
	}
	if _, err := os.Stat(path); err != nil {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{
			"status": "error", "message": "插件包不存在: " + err.Error(),
		})
		return
	}
	info, err := s.store.InstallPlugin(path)
	if err != nil {
		s.AddLog("插件安装失败: "+err.Error(), "error")
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	// 装完立刻热注册，UI 不必再单独调一次 reload
	if s.loadPlugin != nil {
		if err := s.loadPlugin(info.ID); err != nil {
			s.AddLog("插件已安装但加载失败: "+err.Error(), "error")
			s.writeJSON(w, http.StatusInternalServerError, map[string]any{
				"status": "error", "message": err.Error(),
			})
			return
		}
	}
	s.AddLog("已安装插件: "+info.ID+" "+info.Version, "success")
	s.writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok", "id": info.ID, "version": info.Version,
	})
}

func (s *Server) handleUninstallPlugin(w http.ResponseWriter, id string) {
	if id == "" {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{
			"status": "error", "message": "缺少插件 id",
		})
		return
	}
	if err := s.store.UninstallPlugin(id); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	if s.unloadPlugin != nil {
		_ = s.unloadPlugin(id)
	}
	s.AddLog("已卸载插件: "+id, "info")
	s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

// handleGetLogs 返回最近的日志（最多 400 条，与安卓端日志页一致）。
func (s *Server) handleGetLogs(w http.ResponseWriter) {
	logs := s.Logs()
	if len(logs) > 400 {
		logs = logs[len(logs)-400:]
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"logs": logs})
}

func (s *Server) handleGetDevices(w http.ResponseWriter) {
	devices, err := s.store.LoadTrustedDevices()
	if err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"devices": devices})
}

func (s *Server) handleDeleteDevice(w http.ResponseWriter, deviceID string) {
	if deviceID == "" {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{
			"status": "error", "message": "missing device id",
		})
		return
	}
	if err := s.store.RemoveTrustedDevice(deviceID); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	s.AddLog("已删除设备: "+deviceID, "info")
	s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

// settingsView 是 UI 侧看到的设置（不把 encryption_key 之类的内部字段暴露出去）。
type settingsView struct {
	Port         int    `json:"port"`
	AcceptAll    bool   `json:"accept_all"`
	MarketSource string `json:"market_source"`
	CurrentPort  int    `json:"current_port"`
}

func (s *Server) handleGetSettings(w http.ResponseWriter) {
	cfg, err := s.store.LoadConfig()
	if err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	port := cfg.LastPort
	if port == 0 {
		port = defaultPort
	}
	s.writeJSON(w, http.StatusOK, settingsView{
		Port:         port,
		AcceptAll:    cfg.AcceptAll,
		MarketSource: cfg.MarketSource,
		CurrentPort:  s.Port(),
	})
}

// handlePutSettings 保存设置。端口只存「下次启动用」的值（LastPort）——
// 运行中的监听端口要改得重启服务，这里明确只写配置，不假装能热改。
func (s *Server) handlePutSettings(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Port         *int    `json:"port"`
		AcceptAll    *bool   `json:"accept_all"`
		MarketSource *string `json:"market_source"`
	}
	if !parseBody(r, &body) {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{
			"status": "error", "message": "Invalid JSON",
		})
		return
	}
	cfg, err := s.store.LoadConfig()
	if err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	if body.Port != nil {
		if *body.Port < 1 || *body.Port > 65535 {
			s.writeJSON(w, http.StatusBadRequest, map[string]any{
				"status": "error", "message": "端口超出范围",
			})
			return
		}
		cfg.LastPort = *body.Port
	}
	if body.AcceptAll != nil {
		cfg.AcceptAll = *body.AcceptAll
	}
	if body.MarketSource != nil {
		cfg.MarketSource = *body.MarketSource
	}
	if err := s.store.SaveConfig(cfg); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	s.AddLog("设置已保存", "success")
	s.handleGetSettings(w)
}

// parseCommandBody 读命令的 name/command，两边都必须非空。
func parseCommandBody(r *http.Request) (store.Command, bool) {
	var body store.Command
	if !parseBody(r, &body) {
		return store.Command{}, false
	}
	body.Name = strings.TrimSpace(body.Name)
	body.Command = strings.TrimSpace(body.Command)
	if body.Name == "" || body.Command == "" {
		return store.Command{}, false
	}
	return body, true
}

func (s *Server) handleCreateCommand(w http.ResponseWriter, r *http.Request) {
	cmd, ok := parseCommandBody(r)
	if !ok {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{
			"status": "error", "message": "命令名与内容都不能为空",
		})
		return
	}
	if err := s.store.AddCommand(cmd.Name, cmd.Command); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	commands, _ := s.store.LoadCommands()
	s.writeJSON(w, http.StatusOK, map[string]any{"commands": commands})
}

func (s *Server) handleUpdateCommand(w http.ResponseWriter, r *http.Request, index int) {
	cmd, ok := parseCommandBody(r)
	if !ok {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{
			"status": "error", "message": "命令名与内容都不能为空",
		})
		return
	}
	commands, err := s.store.LoadCommands()
	if err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	if index < 0 || index >= len(commands) {
		s.writeJSON(w, http.StatusNotFound, map[string]any{
			"status": "error", "message": "命令不存在",
		})
		return
	}
	commands[index] = cmd
	if err := s.store.SaveCommands(commands); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"commands": commands})
}

func (s *Server) handleDeleteCommand(w http.ResponseWriter, index int) {
	commands, err := s.store.LoadCommands()
	if err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	if index < 0 || index >= len(commands) {
		s.writeJSON(w, http.StatusNotFound, map[string]any{
			"status": "error", "message": "命令不存在",
		})
		return
	}
	if err := s.store.RemoveCommand(index); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"status": "error", "message": err.Error(),
		})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

// matchCommandID 匹配 /api/commands/{index}（{index} 必须是数字）。
func matchCommandID(path string) bool {
	_, err := strconv.Atoi(strings.TrimPrefix(path, "/api/commands/"))
	return err == nil
}

func commandID(path string) int {
	id, _ := strconv.Atoi(strings.TrimPrefix(path, "/api/commands/"))
	return id
}
