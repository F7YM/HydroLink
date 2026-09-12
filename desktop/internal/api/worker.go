package api

import (
	"encoding/json"
	"net"
	"net/http"
	"path/filepath"
	"strings"
	"time"

	"github.com/gen2brain/beeep"

	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/store"
	"hydrolink-desktop/internal/system"
)

// WorkerHandler serves the internal channel between Kit and plugin workers
// (mounted under /api/hydro/worker/):
//
//	GET  /poll                         worker long-polls for requests
//	POST /respond                      worker posts results
//	POST /store                        kit.storage (server scope; watch scope pending)
//	POST /notify | /music | /lock | /commands   capability APIs (permission-gated)
//	GET  /asset                        plugin's own packaged files (loadWasm/images)
//
// All capability endpoints are keyed by the package id and require the
// permission declared in that plugin's manifest.json.
type WorkerHandler struct {
	bridge *hydrokit.WorkerBridge
	st     *store.Store
}

// NewWorkerHandler builds the worker channel handler.
func NewWorkerHandler(bridge *hydrokit.WorkerBridge, st *store.Store) http.Handler {
	return &WorkerHandler{bridge: bridge, st: st}
}

func (h *WorkerHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/hydro/worker")
	path = strings.TrimPrefix(path, "/")
	switch {
	case path == "poll" && r.Method == http.MethodGet:
		h.bridge.HandlePoll(w, r)
	case path == "respond" && r.Method == http.MethodPost:
		h.bridge.HandleRespond(w, r)
	case path == "store" && r.Method == http.MethodPost:
		h.handleStore(w, r)
	case path == "context" && r.Method == http.MethodPost:
		h.handleContext(w, r)
	case path == "notify" && r.Method == http.MethodPost:
		h.handleNotify(w, r)
	case path == "music" && r.Method == http.MethodPost:
		h.handleMusic(w, r)
	case path == "lock" && r.Method == http.MethodPost:
		h.handleLock(w, r)
	case path == "commands" && r.Method == http.MethodPost:
		h.handleCommands(w, r)
	case path == "asset" && r.Method == http.MethodGet:
		h.handleAsset(w, r)
	default:
		http.Error(w, "worker endpoint not found", http.StatusNotFound)
	}
}

// ---- capability permission gate ----

func (h *WorkerHandler) requirePermission(w http.ResponseWriter, id, perm string) bool {
	m, err := h.st.GetPluginManifest(id)
	if err != nil {
		writeWorkerError(w, http.StatusForbidden, "plugin 不存在或清单不可读")
		return false
	}
	for _, p := range m.Permissions {
		if p == perm {
			return true
		}
	}
	writeWorkerError(w, http.StatusForbidden, "权限不足: "+perm)
	return false
}

// ---- kit.storage ----

func (h *WorkerHandler) handleStore(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Package string `json:"package"`
		Op      string `json:"op"` // get | set | remove | clear
		Key     string `json:"key"`
		Value   any    `json:"value"`
		Scope   string `json:"scope"`
	}
	if !parseWorkerBody(r, &body) {
		writeWorkerError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if !h.requirePermission(w, body.Package, "storage") {
		return
	}
	if body.Scope == "" {
		body.Scope = "server"
	}
	if body.Scope != "server" {
		writeWorkerError(w, http.StatusNotImplemented, "watch 端存储将在容器协议里程碑接入，当前仅支持 scope=server")
		return
	}
	kv, err := h.st.LoadPluginKV(body.Package)
	if err != nil {
		writeWorkerError(w, http.StatusInternalServerError, "读取存储失败")
		return
	}
	switch body.Op {
	case "get":
		v, found := kv[body.Key]
		writeJSON(w, http.StatusOK, map[string]any{"found": found, "value": v})
	case "set":
		kv[body.Key] = body.Value
		if err := h.st.SavePluginKV(body.Package, kv); err != nil {
			writeWorkerError(w, http.StatusInternalServerError, "写入存储失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	case "remove":
		delete(kv, body.Key)
		if err := h.st.SavePluginKV(body.Package, kv); err != nil {
			writeWorkerError(w, http.StatusInternalServerError, "写入存储失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	case "clear":
		if err := h.st.SavePluginKV(body.Package, map[string]any{}); err != nil {
			writeWorkerError(w, http.StatusInternalServerError, "写入存储失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	default:
		writeWorkerError(w, http.StatusBadRequest, "未知操作: "+body.Op)
	}
}

// ---- capabilities ----

func (h *WorkerHandler) handleNotify(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Package string `json:"package"`
		Title   string `json:"title"`
		Text    string `json:"text"`
	}
	if !parseWorkerBody(r, &body) {
		writeWorkerError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if !h.requirePermission(w, body.Package, "notify") {
		return
	}
	_ = beeep.Notify(body.Title, body.Text, "")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (h *WorkerHandler) handleMusic(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Package string `json:"package"`
		Action  string `json:"action"`
	}
	if !parseWorkerBody(r, &body) {
		writeWorkerError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if !h.requirePermission(w, body.Package, "music") {
		return
	}
	switch body.Action {
	case system.ActionPlay, system.ActionPause, system.ActionPlayPause, system.ActionNext, system.ActionPrevious:
	default:
		writeWorkerError(w, http.StatusBadRequest, "无效动作: "+body.Action)
		return
	}
	if !system.ControlMusic(body.Action) {
		writeWorkerError(w, http.StatusInternalServerError, "没有活动的媒体会话")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (h *WorkerHandler) handleLock(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Package string `json:"package"`
	}
	if !parseWorkerBody(r, &body) {
		writeWorkerError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if !h.requirePermission(w, body.Package, "lock") {
		return
	}
	if !system.LockScreen() {
		writeWorkerError(w, http.StatusInternalServerError, "锁屏失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (h *WorkerHandler) handleCommands(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Package string `json:"package"`
		Op      string `json:"op"` // list | run
		Index   int    `json:"index"`
	}
	if !parseWorkerBody(r, &body) {
		writeWorkerError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if !h.requirePermission(w, body.Package, "commands") {
		return
	}
	commands, _ := h.st.LoadCommands()
	switch body.Op {
	case "list":
		out := make([]map[string]any, 0, len(commands))
		for i, c := range commands {
			out = append(out, map[string]any{"id": i, "name": c.Name, "command": c.Command})
		}
		writeJSON(w, http.StatusOK, map[string]any{"commands": out})
	case "run":
		if body.Index < 0 || body.Index >= len(commands) {
			writeWorkerError(w, http.StatusBadRequest, "无效命令序号")
			return
		}
		res := system.RunCommand(commands[body.Index].Command)
		if !res.Success {
			writeWorkerError(w, http.StatusInternalServerError, "命令失败: "+res.Output)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"returncode": res.ExitCode, "output": res.Output})
	default:
		writeWorkerError(w, http.StatusBadRequest, "未知操作: "+body.Op)
	}
}

// ---- assets ----

func (h *WorkerHandler) handleAsset(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("package")
	rel := r.URL.Query().Get("path")
	if id == "" || rel == "" {
		writeWorkerError(w, http.StatusBadRequest, "缺少 package/path")
		return
	}
	clean := filepath.Clean(filepath.FromSlash(rel))
	if clean == "." || filepath.IsAbs(clean) ||
		strings.HasPrefix(clean, ".."+string(filepath.Separator)) || clean == ".." {
		writeWorkerError(w, http.StatusBadRequest, "非法路径")
		return
	}
	root := filepath.Join(h.st.PluginsDir(), id)
	full := filepath.Join(root, clean)
	if filepath.Dir(full) != root && !strings.HasPrefix(filepath.Dir(full), root+string(filepath.Separator)) {
		writeWorkerError(w, http.StatusBadRequest, "非法路径")
		return
	}
	http.ServeFile(w, r, full)
}

// ---- helpers ----

func parseWorkerBody(r *http.Request, out any) bool {
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(out) == nil
}

func writeWorkerError(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": msg})
}

// writeJSON is the package-level JSON writer (worker endpoints).
func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

// ---- plugin WebUI (web/ assets) ----

// handleWebUI serves a plugin's packaged web/ resources:
//
//	GET /api/hydro/webui/{package}/{path...}  (index.html default)
//
// The page is served same-origin through the desktop asset-server proxy, so
// the plugin's UI can call /api/hydro/worker/* with its own package id (per
// its manifest permissions).
func (s *Server) handleWebUI(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	rest := strings.TrimPrefix(path, "/api/hydro/webui/")
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) == 0 || parts[0] == "" {
		http.Error(w, "缺少插件 ID", http.StatusBadRequest)
		return
	}
	id := parts[0]
	rel := ""
	if len(parts) == 2 {
		rel = parts[1]
	}
	if rel == "" {
		rel = "index.html"
	}
	data, err := s.store.ReadPluginFile(id, "web/"+rel)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	ct := webContentType(rel)
	w.Header().Set("Content-Type", ct)
	_, _ = w.Write(data)
}

func webContentType(name string) string {
	switch {
	case strings.HasSuffix(name, ".html"):
		return "text/html; charset=utf-8"
	case strings.HasSuffix(name, ".js"):
		return "text/javascript; charset=utf-8"
	case strings.HasSuffix(name, ".css"):
		return "text/css; charset=utf-8"
	case strings.HasSuffix(name, ".json"):
		return "application/json; charset=utf-8"
	case strings.HasSuffix(name, ".png"):
		return "image/png"
	case strings.HasSuffix(name, ".svg"):
		return "image/svg+xml"
	default:
		return "application/octet-stream"
	}
}

// ---- WASM plugin ABI v1: context builder ----

// handleContext assembles the ABI v1 context JSON for a WASM plugin: the
// pre-fetched, permission-gated data a pure-function plugin needs (request,
// server time, host system metrics, music, its own server-scope storage).
func (h *WorkerHandler) handleContext(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Package string          `json:"package"`
		Surface string          `json:"surface"`
		Item    string          `json:"item"`
		Req     json.RawMessage `json:"req"`
	}
	if !parseWorkerBody(r, &body) {
		writeWorkerError(w, http.StatusBadRequest, "invalid body")
		return
	}
	m, err := h.st.GetPluginManifest(body.Package)
	if err != nil {
		writeWorkerError(w, http.StatusForbidden, "plugin 不存在或清单不可读")
		return
	}
	has := func(perm string) bool {
		for _, p := range m.Permissions {
			if p == perm {
				return true
			}
		}
		return false
	}

	now := time.Now()
	ctx := map[string]any{
		"abi":     1,
		"package": body.Package,
		"surface": body.Surface,
		"item":    body.Item,
		"time": map[string]any{
			"unix":   now.Unix(),
			"iso":    now.Format(time.RFC3339),
			"hour":   now.Hour(),
			"minute": now.Minute(),
		},
		"server": map[string]any{
			"base": "http://" + apiBaseHost(r),
		},
	}
	if len(body.Req) > 0 {
		ctx["req"] = json.RawMessage(body.Req)
	}

	if has("system") {
		if p, err := system.GetPerf(); err == nil {
			ctx["system"] = map[string]any{
				"cpu":        p.CPU,
				"memPercent": p.MemPercent,
				"memUsedMB":  p.MemUsedMB,
				"memTotalMB": p.MemTotalMB,
			}
		}
	}
	if has("music") {
		info := system.GetMusic()
		ctx["music"] = map[string]any{
			"hasMedia": info.HasMedia,
			"title":    info.Title,
			"artist":   info.Artist,
			"playing":  info.Playing,
		}
	}
	if has("storage") || has("system") || has("music") {
		if kv, err := h.st.LoadPluginKV(body.Package); err == nil {
			ctx["storage"] = kv
		}
	}

	writeJSON(w, http.StatusOK, ctx)
}

// apiBaseHost returns the LAN-reachable host:port for the current server
// (LAN IP + the port this request arrived on).
func apiBaseHost(r *http.Request) string {
	port := "9000"
	if _, p, err := net.SplitHostPort(r.Host); err == nil && p != "" {
		port = p
	}
	return net.JoinHostPort(LocalIP(), port)
}
