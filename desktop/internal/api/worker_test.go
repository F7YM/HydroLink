package api

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"hydrolink-desktop/internal/hydrokit"
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
	h := NewWorkerHandler(hydrokit.NewWorkerBridge(), st)
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
	h := NewWorkerHandler(hydrokit.NewWorkerBridge(), st)
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
	srv.SetWorkerHandler(NewWorkerHandler(hydrokit.NewWorkerBridge(), st))
	h := srv.Handler()

	// worker channel is served before the plugin dispatcher
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/hydro/worker/store",
		bytes.NewBufferString(`{"package":"com.t.worker","op":"get","key":"k"}`)))
	if rr.Code != http.StatusOK || !strings.Contains(rr.Body.String(), "found") {
		t.Fatalf("worker store via server -> %d: %s", rr.Code, rr.Body.String())
	}
}

// ---- kit.music.now / worker platform ----

const wkMusicManifest = `{
  "id": "com.t.music",
  "name": "媒体测试",
  "version": "0.1.0",
  "entry": "server.js",
  "apps": [{"id":"main","name":"主页"}],
  "permissions": ["storage","music"]
}`

// workerMusicStore 装一个带 music 权限的插件（kit.music.now 要用）。
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

// TestMusicNowEndpoint：kit.music.now() 读当前媒体，与 WASM 的 ctx.music 同构，
// 只要求 music 权限（此前 JS 插件根本没有读当前媒体的途径）。
func TestMusicNowEndpoint(t *testing.T) {
	st := workerMusicStore(t)
	h := NewWorkerHandler(hydrokit.NewWorkerBridge(), st)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/hydro/worker/music",
		bytes.NewBufferString(`{"package":"com.t.music","op":"now"}`)))
	if rr.Code != http.StatusOK {
		t.Fatalf("music now -> %d: %s", rr.Code, rr.Body.String())
	}
	var got map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"hasMedia", "title", "artist", "playing", "artUrl", "artBlur"} {
		if _, ok := got[k]; !ok {
			t.Fatalf("music now 缺少 %s: %+v", k, got)
		}
	}
	if !strings.HasPrefix(got["artUrl"].(string), "http://") {
		t.Fatalf("artUrl 应当是手表可达的地址: %v", got["artUrl"])
	}
	// 控制播放仍然走 action；未知 action 要报错
	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/hydro/worker/music",
		bytes.NewBufferString(`{"package":"com.t.music","action":"nonsense"}`)))
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("未知 action -> %d", rr.Code)
	}
}

func TestWorkerPlatformEndpoint(t *testing.T) {
	st := workerMusicStore(t)
	h := NewWorkerHandler(hydrokit.NewWorkerBridge(), st)
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
