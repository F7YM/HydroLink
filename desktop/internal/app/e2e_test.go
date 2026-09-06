package app

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"hydrolink-desktop/internal/hydrokit"
)

// TestJSRuntimeProductionChain boots the real NewServer wiring (isolated
// XDG_CONFIG_HOME), installs the SHIPPED weather example package from
// desktop/examples/com.demo.weather (manifest/server.js/web), starts a Node
// process running the REAL WorkerBundle against the real server, and drives
// the production flow: view, watch-store upload, event -> server-storage
// write, re-view, widget surface and the plugin WebUI.
func TestJSRuntimeProductionChain(t *testing.T) {
	nodePath, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not available")
	}
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())

	// The shipped example package is the artifact under test.
	exampleDir, err := filepath.Abs(filepath.Join("..", "..", "examples", "com.demo.weather"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(exampleDir, "manifest.json")); err != nil {
		t.Skipf("example package missing: %v", err)
	}

	_, st, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}

	// Package the example dir into an .hpk exactly like the release flow.
	hpk := filepath.Join(t.TempDir(), "com.demo.weather.hpk")
	{
		var buf bytes.Buffer
		zw := zip.NewWriter(&buf)
		for _, name := range []string{"manifest.json", "server.js", "web/index.html"} {
			data, err := os.ReadFile(filepath.Join(exampleDir, name))
			if err != nil {
				t.Fatalf("read example %s: %v", name, err)
			}
			w, err := zw.Create(name)
			if err != nil {
				t.Fatal(err)
			}
			_, _ = w.Write(data)
		}
		_ = zw.Close()
		if err := os.WriteFile(hpk, buf.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := st.InstallPlugin(hpk); err != nil {
		t.Fatalf("install: %v", err)
	}

	// Boot again so the newly installed plugin registers (like a real app).
	srv2, _, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer (2nd): %v", err)
	}
	ts := httptest.NewServer(srv2.Handler())
	defer ts.Close()

	code, err := st.ReadPluginFile("com.demo.weather", "server.js")
	if err != nil {
		t.Fatalf("read entry: %v", err)
	}
	bundle := hydrokit.WorkerBundle("com.demo.weather", string(code), ts.URL)

	dir := t.TempDir()
	bundlePath := filepath.Join(dir, "bundle.js")
	if err := os.WriteFile(bundlePath, bundle, 0o644); err != nil {
		t.Fatal(err)
	}
	runnerPath := filepath.Join(dir, "runner.js")
	runner := `global.postMessage = function () {};
new Function(require("fs").readFileSync(process.argv[2], "utf8"))();
setInterval(function () {}, 1000);
`
	if err := os.WriteFile(runnerPath, []byte(runner), 0o644); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(nodePath, runnerPath, bundlePath)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill() }()

	viewURL := ts.URL + "/api/hydro/com.demo.weather/app/main/view"
	viewReq := func(store string) *httptest.ResponseRecorder {
		req, _ := http.NewRequest(http.MethodPost, viewURL, nil)
		if store != "" {
			req.Header.Set("X-Hydro-Store", store)
		}
		rr := httptest.NewRecorder()
		srv2.Handler().ServeHTTP(rr, req)
		return rr
	}

	// The worker connects on its first poll; retry until the app view answers.
	var rr *httptest.ResponseRecorder
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		rr = viewReq("")
		if rr.Code == http.StatusOK {
			break
		}
		if rr == nil {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if rr == nil || rr.Code != http.StatusOK {
		t.Fatalf("view -> %d: %s", rr.Code, rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), "示例天气") {
		t.Fatalf("view body missing title: %s", rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), `"nav"`) {
		t.Fatalf("view body missing nav node: %s", rr.Body.String())
	}

	// Watch-store upload: the snapshot rides X-Hydro-Store; the plugin reads
	// powerSave from the watch scope (device-local preference).
	rr = viewReq(`{"powerSave":false}`)
	if rr.Code != http.StatusOK || !strings.Contains(rr.Body.String(), `"checked":false`) {
		t.Fatalf("view with uploaded watch store -> %d: %s", rr.Code, rr.Body.String())
	}

	// Event (place=深圳) -> plugin writes SERVER storage; the re-view (no
	// upload) shows it — the cross-device sync path.
	evtBody := `{"event":{"handler":"place","source":"change","value":"深圳"}}`
	ereq, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/hydro/com.demo.weather/app/main/event",
		bytes.NewBufferString(evtBody))
	er := httptest.NewRecorder()
	srv2.Handler().ServeHTTP(er, ereq)
	if er.Code != http.StatusOK {
		t.Fatalf("event -> %d: %s", er.Code, er.Body.String())
	}
	time.Sleep(400 * time.Millisecond) // let the worker re-enter the poll
	rr = viewReq("")
	if rr.Code != http.StatusOK || !strings.Contains(rr.Body.String(), "深圳 · 23°C 晴") {
		t.Fatalf("view after event -> %d: %s", rr.Code, rr.Body.String())
	}

	// Event (toggle) -> WATCH write -> the response carries storage for the
	// container to persist on the device.
	toggleBody := `{"event":{"handler":"toggle","source":"change"}}`
	treq, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/hydro/com.demo.weather/app/main/event",
		bytes.NewBufferString(toggleBody))
	tr := httptest.NewRecorder()
	srv2.Handler().ServeHTTP(tr, treq)
	if tr.Code != http.StatusOK {
		t.Fatalf("toggle -> %d: %s", tr.Code, tr.Body.String())
	}
	var trResp struct {
		Storage map[string]any `json:"storage"`
	}
	if err := json.Unmarshal(tr.Body.Bytes(), &trResp); err != nil {
		t.Fatalf("toggle resp json: %v — %s", err, tr.Body.String())
	}
	if _, ok := trResp.Storage["powerSave"]; !ok {
		t.Fatalf("toggle storage = %+v, want powerSave", trResp.Storage)
	}

	// Widget surface + plugin WebUI.
	wr2 := httptest.NewRecorder()
	srv2.Handler().ServeHTTP(wr2, httptest.NewRequest(http.MethodPost,
		ts.URL+"/api/hydro/com.demo.weather/widget/now/view", nil))
	if wr2.Code != http.StatusOK || !strings.Contains(wr2.Body.String(), "23°C") {
		t.Fatalf("widget view -> %d: %s", wr2.Code, wr2.Body.String())
	}
	ureq, _ := http.NewRequest(http.MethodGet,
		ts.URL+"/api/hydro/webui/com.demo.weather/index.html", nil)
	ur := httptest.NewRecorder()
	srv2.Handler().ServeHTTP(ur, ureq)
	if ur.Code != http.StatusOK || !strings.Contains(ur.Body.String(), "示例天气") {
		t.Fatalf("webui -> %d: %s", ur.Code, ur.Body.String())
	}
}
