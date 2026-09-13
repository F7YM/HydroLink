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
	"strconv"
	"strings"
	"sync"
	"time"

	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/store"
	"hydrolink-desktop/internal/system"
)

const (
	// APILevel mirrors the Android server's API_LEVEL.
	APILevel = 6
	// Version mirrors the Android server's VERSION.
	Version = "1.0.0"
)

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
	loadPlugin   func(id string) error
	unloadPlugin func(id string) error
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

// SetWorkerBridge records the WorkerBridge backing JS plugins (GUI uses it to
// hook wake events).
func (s *Server) SetWorkerBridge(b *hydrokit.WorkerBridge) {
	s.workerBridge = b
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
			"status": "ok", "api_level": APILevel, "version": Version,
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
	if len(body.DeviceName) > 50 {
		body.DeviceName = body.DeviceName[:50]
	}

	cfg, _ := s.store.LoadConfig()

	if s.store.IsTrusted(body.DeviceID) {
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
	_ = s.store.RemovePendingPair(body.DeviceID)
	if body.Action == "accept" {
		_ = s.store.AddTrustedDevice(body.DeviceID, body.DeviceName)
		s.AddLog("已信任设备: "+shortID(body.DeviceID), "success")
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
