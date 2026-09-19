package hydrokit

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestBridgeRoundtrip(t *testing.T) {
	b := NewWorkerBridge()
	mux := http.NewServeMux()
	mux.HandleFunc("/poll", b.HandlePoll)
	mux.HandleFunc("/respond", b.HandleRespond)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	// Simulated worker: poll once, answer with a view JSON.
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		client := &http.Client{Timeout: 30 * time.Second}
		// First poll: block until the request arrives.
		resp, err := client.Get(srv.URL + "/poll?package=com.t.w")
		if err != nil {
			t.Errorf("poll: %v", err)
			return
		}
		var msg struct {
			Req struct {
				ID      int64           `json:"id"`
				Surface string          `json:"surface"`
				Item    string          `json:"item"`
				Req     json.RawMessage `json:"req"`
			} `json:"req"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&msg)
		resp.Body.Close()
		if msg.Req.Surface != "app" || msg.Req.Item != "main" {
			t.Errorf("poll req = %+v", msg.Req)
		}
		var rq Request
		_ = json.Unmarshal(msg.Req.Req, &rq)
		if rq.Trigger != TriggerOpen || rq.Screen.Shape != "circle" {
			t.Errorf("poll req.req = %+v", rq)
		}
		body, _ := json.Marshal(map[string]any{
			"package": "com.t.w", "id": msg.Req.ID,
			"result": map[string]any{"root": map[string]any{"type": "text", "props": map[string]any{"text": "hello"}}},
		})
		rr, _ := http.Post(srv.URL+"/respond", "application/json", bytes.NewReader(body))
		if rr != nil {
			rr.Body.Close()
		}
	}()

	payload, _ := json.Marshal(map[string]any{
		"surface": "app", "item": "main",
		"req": Request{Trigger: TriggerOpen, Screen: Screen{Shape: "circle", Width: 466}},
	})
	// Give the poller a moment to register its session before calling.
	time.Sleep(200 * time.Millisecond)
	data, err := b.Call("com.t.w", "view", payload)
	if err != nil {
		t.Fatalf("call: %v", err)
	}
	wg.Wait()
	if !strings.Contains(string(data), "hello") {
		t.Fatalf("data = %s", data)
	}
}

func TestBridgeOffline(t *testing.T) {
	b := NewWorkerBridge()
	var woke string
	var mu sync.Mutex
	b.SetOnNeedWake(func(pkg string) { mu.Lock(); woke = pkg; mu.Unlock() })
	_, err := b.Call("com.t.off", "view", []byte(`{"surface":"app","item":"main","req":{}}`))
	if err == nil || !strings.Contains(err.Error(), "offline") {
		t.Fatalf("err = %v, want offline", err)
	}
	// wake fires on a goroutine; give it a moment.
	for i := 0; i < 50; i++ {
		mu.Lock()
		w := woke
		mu.Unlock()
		if w == "com.t.off" {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("wake = %q, want com.t.off", woke)
}

func TestBridgeTimeout(t *testing.T) {
	b := NewWorkerBridge()
	b.timeout = 150 * time.Millisecond
	b.pollWindow = 2 * time.Second
	_ = b.ensureConn("com.t.slow") // register a session that never answers
	start := time.Now()
	_, err := b.Call("com.t.slow", "view", []byte(`{"surface":"app","item":"main","req":{}}`))
	if err == nil || !strings.Contains(err.Error(), "timed out") {
		t.Fatalf("err = %v, want timeout", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("timeout took too long: %v", time.Since(start))
	}
}

func TestJSPluginWithFakeCaller(t *testing.T) {
	var gotPkg, gotPayload string
	fake := callerFunc(func(pkg, action string, payload []byte) ([]byte, error) {
		gotPkg, gotPayload = pkg, string(payload)
		return []byte(`{"root":{"type":"text","props":{"text":"hi"}}}`), nil
	})
	m := &Manifest{
		Package: Package{ID: "com.t.js", Name: "JS", Version: "0.1.0"},
		Author:  "测试作者",
		Apps:    []Item{{ID: "main", Name: "主页"}},
	}
	p := NewJSPlugin(m, fake)

	rt := NewRuntime(nil)
	if err := rt.Register(p); err != nil {
		t.Fatalf("register: %v", err)
	}
	h := NewHandler(rt)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost,
		"/api/hydro/com.t.js/app/main/view?shape=pill&width=192", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("view -> %d: %s", rr.Code, rr.Body.String())
	}
	if gotPkg != "com.t.js" {
		t.Fatalf("caller pkg = %q", gotPkg)
	}
	var payload struct {
		Surface string  `json:"surface"`
		Item    string  `json:"item"`
		Req     Request `json:"req"`
	}
	if err := json.Unmarshal([]byte(gotPayload), &payload); err != nil {
		t.Fatalf("payload json: %v", err)
	}
	if payload.Surface != "app" || payload.Item != "main" {
		t.Fatalf("payload = %+v", payload)
	}
	if payload.Req.Screen.Shape != "pill" || payload.Req.Screen.Width != 192 {
		t.Fatalf("payload.req = %+v", payload.Req)
	}
	if rr.Body.String() == "" || !strings.Contains(rr.Body.String(), "hi") {
		t.Fatalf("response = %s", rr.Body.String())
	}
}

type callerFunc func(pkg, action string, payload []byte) ([]byte, error)

func (f callerFunc) Call(pkg, action string, payload []byte) ([]byte, error) {
	return f(pkg, action, payload)
}

func TestWorkerBundleSyntaxAndContent(t *testing.T) {
	code := "module.exports = { view: function(s,i,r){ return null } } // q\"uote"
	bundle := WorkerBundle("com.t.bundle", code, "http://127.0.0.1:9000")
	s := string(bundle)
	for _, want := range []string{
		`"com.t.bundle"`, "http://127.0.0.1:9000", "new Function(", "poll?package=", "respond",
		"q\\\"uote",
	} {
		if !strings.Contains(s, want) {
			t.Fatalf("bundle missing %q", want)
		}
	}
	// Syntax check with node if available (parse only — browser APIs unused at parse time).
	if nodePath, err := exec.LookPath("node"); err == nil {
		p := filepath.Join(t.TempDir(), "bundle.js")
		if err := os.WriteFile(p, bundle, 0o644); err != nil {
			t.Fatal(err)
		}
		out, err := runCommand(nodePath, "--check", p)
		if err != nil {
			t.Fatalf("node --check: %v\n%s", err, out)
		}
	}
}

func runCommand(name string, args ...string) (string, error) {
	cmd := exec.Command(name, args...)
	var buf bytes.Buffer
	cmd.Stdout = &buf
	cmd.Stderr = &buf
	err := cmd.Run()
	return buf.String(), err
}
