package api

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/splayer"
	"hydrolink-desktop/internal/store"
)

const wkManifest = `{
  "id": "com.t.worker",
  "name": "Worker测试",
  "version": "0.1.0",
  "entry": "server.js",
  "apps": [{"id":"main","name":"主页"}],
  "permissions": ["storage","notify"]
}`

func workerTestStore(t *testing.T) *store.Store {
	t.Helper()
	st := store.NewIn(t.TempDir())
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range map[string]string{"manifest.json": wkManifest, "server.js": "x"} {
		w, _ := zw.Create(name)
		_, _ = w.Write([]byte(content))
	}
	_ = zw.Close()
	p := filepath.Join(t.TempDir(), "p.hpk")
	if err := os.WriteFile(p, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := st.InstallPlugin(p); err != nil {
		t.Fatal(err)
	}
	return st
}

func TestWorkerStoreEndpoints(t *testing.T) {
	st := workerTestStore(t)
	h := NewWorkerHandler(hydrokit.NewWorkerBridge(), st, nil)
	post := func(body string, wantCode int) *httptest.ResponseRecorder {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/hydro/worker/store",
			bytes.NewBufferString(body)))
		if rr.Code != wantCode {
			t.Fatalf("body %s -> %d (want %d): %s", body, rr.Code, wantCode, rr.Body.String())
		}
		return rr
	}
	post(`{"package":"com.t.worker","op":"set","key":"place","value":"上海"}`, http.StatusOK)
	rr := post(`{"package":"com.t.worker","op":"get","key":"place"}`, http.StatusOK)
	var got map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &got)
	if got["found"] != true || got["value"] != "上海" {
		t.Fatalf("get = %+v", got)
	}
	post(`{"package":"com.t.worker","op":"remove","key":"place"}`, http.StatusOK)
	rr = post(`{"package":"com.t.worker","op":"get","key":"place"}`, http.StatusOK)
	_ = json.Unmarshal(rr.Body.Bytes(), &got)
	if got["found"] != false {
		t.Fatalf("get after remove = %+v", got)
	}
	// watch scope is not wired yet -> 501
	post(`{"package":"com.t.worker","op":"set","key":"k","value":1,"scope":"watch"}`, http.StatusNotImplemented)
	// missing permission -> 403
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/hydro/worker/notify",
		bytes.NewBufferString(`{"package":"com.t.worker","title":"x","text":"y"}`)))
	if rr.Code != http.StatusOK {
		t.Fatalf("notify (has perm) -> %d", rr.Code)
	}
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/hydro/worker/lock",
		bytes.NewBufferString(`{"package":"com.t.worker"}`)))
	if rr.Code != http.StatusForbidden {
		t.Fatalf("lock (no perm) -> %d, want 403", rr.Code)
	}
}

func TestWorkerAssetSecurity(t *testing.T) {
	st := workerTestStore(t)
	h := NewWorkerHandler(hydrokit.NewWorkerBridge(), st, nil)
	dir := filepath.Join(st.PluginsDir(), "com.t.worker")
	if err := os.MkdirAll(filepath.Join(dir, "assets"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "assets", "model.wasm"), []byte("wasm-bytes"), 0o644); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		path string
		want int
	}{
		{"?package=com.t.worker&path=assets/model.wasm", http.StatusOK},
		{"?package=com.t.worker&path=../secret", http.StatusBadRequest},
		{"?package=com.t.worker&path=../../etc/passwd", http.StatusBadRequest},
		{"?package=com.t.worker&path=.", http.StatusBadRequest},
		{"?package=com.t.other&path=assets/model.wasm", http.StatusNotFound}, // no dir
	}
	for _, c := range cases {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/hydro/worker/asset"+c.path, nil))
		if rr.Code != c.want {
			t.Fatalf("%s -> %d (want %d)", c.path, rr.Code, c.want)
		}
	}
}

func TestServerMountsWorkerChannel(t *testing.T) {
	st := workerTestStore(t)
	srv := New(st)
	srv.SetWorkerHandler(NewWorkerHandler(hydrokit.NewWorkerBridge(), st, nil))
	h := srv.Handler()

	// worker channel is served before the plugin dispatcher
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/hydro/worker/store",
		bytes.NewBufferString(`{"package":"com.t.worker","op":"get","key":"k"}`)))
	if rr.Code != http.StatusOK || !strings.Contains(rr.Body.String(), "found") {
		t.Fatalf("worker store via server -> %d: %s", rr.Code, rr.Body.String())
	}
}

// ---- SPlayer 歌词集成 ----

const wkMusicManifest = `{
  "id": "com.t.music",
  "name": "歌词测试",
  "version": "0.1.0",
  "entry": "server.js",
  "apps": [{"id":"main","name":"主页"}],
  "permissions": ["storage","music"]
}`

func workerMusicStore(t *testing.T) *store.Store {
	t.Helper()
	st := store.NewIn(t.TempDir())
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range map[string]string{"manifest.json": wkMusicManifest, "server.js": "x"} {
		w, _ := zw.Create(name)
		_, _ = w.Write([]byte(content))
	}
	_ = zw.Close()
	p := filepath.Join(t.TempDir(), "m.hpk")
	if err := os.WriteFile(p, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := st.InstallPlugin(p); err != nil {
		t.Fatal(err)
	}
	return st
}

// fakeSplayer 起一个最小的 SPlayer WebSocket 服务端（收到 get-song-info 就回
// 一条带歌词的曲目信息），并把端口返回给用例。
func fakeSplayer(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	up := websocket.Upgrader{}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			if string(msg) == "PING" {
				_ = conn.WriteMessage(websocket.TextMessage, []byte("PONG"))
				continue
			}
			var m map[string]any
			_ = json.Unmarshal(msg, &m)
			if m["type"] == "get-song-info" {
				_ = conn.WriteMessage(websocket.TextMessage, []byte(`{"type":"song-info","data":{
					"playStatus":"play","name":"Feel Something","artists":"Jaymes Young",
					"currentTime":12.5,"duration":300,
					"lrcData":[{"startTime":10000,"words":[{"word":"Make me feel something, something"}]},
					           {"startTime":15000,"words":[{"word":"下一句"}]}]}}`))
			}
		}
	})}
	go func() { _ = srv.Serve(ln) }()
	t.Cleanup(func() { _ = srv.Close() })
	return ln.Addr().(*net.TCPAddr).Port
}

func TestWorkerPlatformEndpoint(t *testing.T) {
	st := workerMusicStore(t)
	h := NewWorkerHandler(hydrokit.NewWorkerBridge(), st, nil)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/hydro/worker/platform", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("host -> %d", rr.Code)
	}
	var got map[string]string
	if err := json.Unmarshal(rr.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	// 插件 WebUI 按 os 决定显示哪些能力；桌面端就是 GOOS
	if got["os"] != runtime.GOOS || got["app"] != Version || got["arch"] == "" {
		t.Fatalf("host: %+v", got)
	}
}

func TestSplayerToggleDrivesLyrics(t *testing.T) {
	st := workerMusicStore(t)
	port := fakeSplayer(t)
	sp := splayer.New(nil)
	t.Cleanup(sp.Close)
	h := NewWorkerHandler(hydrokit.NewWorkerBridge(), st, sp)

	call := func(path, body string) map[string]any {
		t.Helper()
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(body)))
		if rr.Code != http.StatusOK {
			t.Fatalf("%s -> %d: %s", path, rr.Code, rr.Body.String())
		}
		var out map[string]any
		if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil {
			t.Fatal(err)
		}
		return out
	}
	status := func() map[string]any {
		return call("/api/hydro/worker/splayer", `{"package":"com.t.music"}`)
	}

	// 开关关着：宿主不会去连
	if s := status(); s["enabled"] != false || s["connected"] != false {
		t.Fatalf("关闭时: %+v", s)
	}
	// 打开开关（插件页面写的就是这两个键）
	kv := map[string]any{kvSplayerLyrics: true, kvSplayerPort: float64(port)}
	if err := st.SavePluginKV("com.t.music", kv); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	var s map[string]any
	for time.Now().Before(deadline) {
		s = status()
		if s["connected"] == true {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if s["connected"] != true {
		t.Fatalf("打开开关后没有连上: %+v", s)
	}
	if s["line"] != "Make me feel something, something" || s["title"] != "Feel Something" {
		t.Fatalf("状态里的歌词: %+v", s)
	}

	// 插件视图的上下文里要带上同一句歌词
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/hydro/worker/context",
		bytes.NewBufferString(`{"package":"com.t.music","surface":"app","item":"main"}`)))
	if rr.Code != http.StatusOK {
		t.Fatalf("context -> %d: %s", rr.Code, rr.Body.String())
	}
	var ctx struct {
		Music struct {
			Lyrics struct {
				Source string `json:"source"`
				Line   string `json:"line"`
				Next   string `json:"next"`
			} `json:"lyrics"`
		} `json:"music"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &ctx); err != nil {
		t.Fatal(err)
	}
	if ctx.Music.Lyrics.Source != "splayer" || ctx.Music.Lyrics.Line != "Make me feel something, something" {
		t.Fatalf("ctx.music.lyrics: %+v", ctx.Music.Lyrics)
	}
	if ctx.Music.Lyrics.Next != "下一句" {
		t.Fatalf("ctx.music.lyrics.next: %+v", ctx.Music.Lyrics)
	}
}
