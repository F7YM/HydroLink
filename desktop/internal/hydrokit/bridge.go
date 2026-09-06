package hydrokit

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// WorkerBridge is the transport between the Kit HTTP dispatcher and a plugin's
// JS worker. A worker cannot listen on a port, so it connects by polling
// /api/hydro/worker/poll (this server, long-poll) for pending requests and
// posts results back to /api/hydro/worker/respond. Requests that arrive for a
// plugin whose worker is offline fail fast and trigger OnNeedWake.
//
// One worker session exists per plugin package; a reconnecting worker replaces
// the old session (its in-flight requests stay pending until timeouts).
type WorkerBridge struct {
	timeout      time.Duration
	pollWindow   time.Duration
	pollInterval time.Duration
	onNeedWake   func(pkg string)
	Logf         func(format string, args ...any)

	mu    sync.Mutex
	conns map[string]*workerConn
}

type workerConn struct {
	mu      sync.Mutex
	queue   []*pendingReq // FIFO of request ids to deliver
	pending map[int64]*pendingReq
	nextID  int64
	notify  chan struct{}
}

type pendingReq struct {
	id      int64
	done    chan workerResp
	payload []byte
}

type workerResp struct {
	data []byte
	err  error
}

// NewWorkerBridge creates a bridge with default timeouts:
// requests time out after 10s, pollers hold up to 25s and re-check every 800ms.
func NewWorkerBridge() *WorkerBridge {
	return &WorkerBridge{
		timeout:      10 * time.Second,
		pollWindow:   25 * time.Second,
		pollInterval: 800 * time.Millisecond,
		conns:        map[string]*workerConn{},
	}
}

// SetOnNeedWake registers a callback fired when a request targets a plugin
// whose worker is not connected (the host uses it to wake the browser worker).
func (b *WorkerBridge) SetOnNeedWake(fn func(pkg string)) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.onNeedWake = fn
}

// Caller is the subset JSPlugin needs.
type Caller interface {
	Call(pkg, action string, payload []byte) ([]byte, error)
}

// Call enqueues a request for the plugin package and blocks for the worker's
// reply. It returns an error when the worker is offline or does not answer in
// time.
func (b *WorkerBridge) Call(pkg, action string, payload []byte) ([]byte, error) {
	b.mu.Lock()
	conn := b.conns[pkg]
	wake := b.onNeedWake
	b.mu.Unlock()
	if conn == nil {
		if b.Logf != nil {
			b.Logf("hydrokit: plugin %s worker offline (request dropped, wake fired)", pkg)
		}
		if wake != nil {
			go wake(pkg)
		}
		return nil, fmt.Errorf("hydrokit: plugin %s worker is offline", pkg)
	}

	conn.mu.Lock()
	conn.nextID++
	id := conn.nextID
	pr := &pendingReq{id: id, done: make(chan workerResp, 1), payload: payload}
	conn.pending[id] = pr
	conn.queue = append(conn.queue, pr)
	conn.mu.Unlock()

	select {
	case <-conn.notify:
	default:
	}

	select {
	case resp := <-pr.done:
		return resp.data, resp.err
	case <-time.After(b.timeout):
		conn.mu.Lock()
		delete(conn.pending, id)
		conn.mu.Unlock()
		return nil, fmt.Errorf("hydrokit: plugin %s worker timed out", pkg)
	}
}

// HandlePoll serves the worker's long-poll endpoint:
//
//	GET /api/hydro/worker/poll?package={id}
//
// It blocks up to the poll window and returns the next pending request:
//
//	{"req": {"id": 1, "action": "view", "surface": "app", "item": "main",
//	         "req": { ...Request JSON... }}}   or   {} when idle.
func (b *WorkerBridge) HandlePoll(w http.ResponseWriter, r *http.Request) {
	pkg := r.URL.Query().Get("package")
	if pkg == "" {
		http.Error(w, "missing package", http.StatusBadRequest)
		return
	}
	if !validPackageID(pkg) {
		http.Error(w, "invalid package", http.StatusBadRequest)
		return
	}

	conn := b.ensureConn(pkg)

	deadline := time.Now().Add(b.pollWindow)
	var pr *pendingReq
	for time.Now().Before(deadline) {
		conn.mu.Lock()
		if len(conn.queue) > 0 {
			pr = conn.queue[0]
			conn.queue = conn.queue[1:]
			conn.mu.Unlock()
			break
		}
		conn.mu.Unlock()

		select {
		case <-conn.notify:
		case <-time.After(b.pollInterval):
		}
	}
	if pr == nil {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte("{}"))
		return
	}

	// The payload is the {surface,item,req} triple; embed it verbatim.
	var payload struct {
		Surface string          `json:"surface"`
		Item    string          `json:"item"`
		Req     json.RawMessage `json:"req"`
	}
	_ = json.Unmarshal(pr.payload, &payload)
	reqMsg := map[string]any{
		"id":      pr.id,
		"surface": payload.Surface,
		"item":    payload.Item,
	}
	if len(payload.Req) > 0 {
		reqMsg["req"] = json.RawMessage(payload.Req)
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"req": reqMsg})
}

// HandleRespond receives the worker's reply:
//
//	POST /api/hydro/worker/respond  {"package": id, "id": 1, "result": {...}, "error": "..."}
func (b *WorkerBridge) HandleRespond(w http.ResponseWriter, r *http.Request) {
	defer r.Body.Close()
	var body struct {
		Package string          `json:"package"`
		ID      int64           `json:"id"`
		Result  json.RawMessage `json:"result"`
		Error   string          `json:"error"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	b.mu.Lock()
	conn := b.conns[body.Package]
	b.mu.Unlock()
	if conn == nil {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"unknown_package"}`))
		return
	}
	conn.mu.Lock()
	pr := conn.pending[body.ID]
	delete(conn.pending, body.ID)
	conn.mu.Unlock()
	if pr == nil {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"unknown_req"}`))
		return
	}
	if body.Error != "" {
		pr.done <- workerResp{err: fmt.Errorf("plugin: %s", body.Error)}
	} else {
		pr.done <- workerResp{data: body.Result}
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

// ensureConn returns (creating if needed) the session for a package.
func (b *WorkerBridge) ensureConn(pkg string) *workerConn {
	b.mu.Lock()
	defer b.mu.Unlock()
	conn := b.conns[pkg]
	if conn == nil {
		conn = &workerConn{pending: map[int64]*pendingReq{}, notify: make(chan struct{}, 1)}
		b.conns[pkg] = conn
		if b.Logf != nil {
			b.Logf("hydrokit: worker connected for %s", pkg)
		}
	}
	return conn
}

func validPackageID(id string) bool {
	if id == "" || len(id) > 128 {
		return false
	}
	for _, c := range id {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}
