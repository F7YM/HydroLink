package api

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
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
