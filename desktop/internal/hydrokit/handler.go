package hydrokit

import (
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

	req := Request{Trigger: triggerForAction(action), Params: map[string]string{}}
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
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		return err
	}
	req.Event = body.Event
	if body.Params != nil {
		req.Params = body.Params
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
