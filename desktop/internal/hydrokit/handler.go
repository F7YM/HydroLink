package hydrokit

import (
	goruntime "runtime"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
)

// Handler serves the HydroKit HTTP API:
//
//	POST /api/hydro/{package}/{surface}/{item}/view     -> tree for surface item
//	POST /api/hydro/{package}/{surface}/{item}/refresh  -> tree (timer/manual)
//	POST /api/hydro/{package}/{surface}/{item}/event    -> optional new tree
//
// surface is "app" or "widget". The dispatcher is the single handler for all
// plugins, so the route set never grows with plugins or items.
type Handler struct {
	rt *Runtime
}

// NewHandler builds the HTTP handler over the given runtime.
func NewHandler(rt *Runtime) *Handler {
	return &Handler{rt: rt}
}

// RoutePrefix is the path namespace handled by HydroKit.
const RoutePrefix = "/api/hydro/"

// HostVersion 是报给插件的宿主应用版本（req.host.app）。由 app 装配处从
// api.Version 注入——hydrokit 不能反过来 import api（会成环），而打包脚本
// 是用 -X 注入 api.Version 的，所以保持单一来源。
var HostVersion = "1.0.0"

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Launcher listing: GET /api/hydro/list
	if strings.Trim(strings.TrimPrefix(r.URL.Path, RoutePrefix), "/") == "list" {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"plugins": h.rt.List()})
		return
	}

	rest := strings.TrimPrefix(r.URL.Path, RoutePrefix)
	parts := strings.Split(strings.Trim(rest, "/"), "/")
	if len(parts) != 4 {
		http.Error(w, "expected /api/hydro/{package}/{surface}/{item}/{action}", http.StatusBadRequest)
		return
	}
	pkg, surface, item, action := parts[0], parts[1], parts[2], parts[3]

	req := Request{
		Trigger: triggerForAction(action),
		Params:  map[string]string{},
		// 插件按平台分支用（安卓端同构，os="android"）
		Host: map[string]string{
			"os":   goruntime.GOOS,
			"arch": goruntime.GOARCH,
			"app":  HostVersion,
		},
	}
	// The container uploads the device-side storage snapshot with each request.
	// HTTP uses the X-Hydro-Store header; transports without headers (e.g. the
	// Android interconnect channel) put the same JSON in the "store" query
	// parameter or in the request body, so accept all three.
	if h := r.Header.Get("X-Hydro-Store"); h != "" {
		var store map[string]any
		if err := json.Unmarshal([]byte(h), &store); err == nil && store != nil {
			req.Store = store
		}
	}
	if req.Store == nil {
		if q := r.URL.Query().Get("store"); q != "" {
			var store map[string]any
			if err := json.Unmarshal([]byte(q), &store); err == nil && store != nil {
				req.Store = store
			}
		}
	}
	if q := r.URL.Query(); q != nil {
		req.Screen = Screen{
			Shape:      q.Get("shape"),
			Width:      atoiDefault(q.Get("width"), 0),
			Height:     atoiDefault(q.Get("height"), 0),
			DPR:        atofDefault(q.Get("dpr"), 0),
			DeviceType: q.Get("dt"),
		}
	}
	if action == "event" {
		if err := decodeEvent(r, &req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
	}

	view, err := h.rt.Dispatch(pkg, surface, item, req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	if view == nil {
		// no change requested
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(view)
}

func triggerForAction(action string) string {
	switch action {
	case "refresh":
		return TriggerRefresh
	case "event":
		return TriggerEvent
	default: // "view" and anything else -> open
		return TriggerOpen
	}
}

func decodeEvent(r *http.Request, req *Request) error {
	if r.Body == nil {
		return nil
	}
	defer r.Body.Close()
	var body struct {
		Event  Event             `json:"event"`
		Params map[string]string `json:"params"`
		Store  map[string]any    `json:"store"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		return err
	}
	req.Event = body.Event
	if body.Params != nil {
		req.Params = body.Params
	}
	// 事件体里也可能带设备侧存储快照（无头部的传输通道）
	if body.Store != nil && req.Store == nil {
		req.Store = body.Store
	}
	req.Trigger = TriggerEvent
	return nil
}

func atoiDefault(s string, def int) int {
	n, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	return n
}

func atofDefault(s string, def float64) float64 {
	f, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return def
	}
	return f
}
