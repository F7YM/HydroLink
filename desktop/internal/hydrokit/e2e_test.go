package hydrokit

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestWorkerBundleRunsInNode executes the REAL WorkerBundle (same code the
// Wails webview would run) inside a Node process against this test's bridge:
// poll -> plugin.view() -> respond -> Call returns the tree. This is the
// closest thing to on-device verification available in-process.
func TestWorkerBundleRunsInNode(t *testing.T) {
	nodePath, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not available")
	}

	b := NewWorkerBridge()
	// Keep the test fast; production defaults are 10s request timeout and a
	// 25s poll window.
	b.timeout = 3 * time.Second
	b.pollWindow = 5 * time.Second
	mux := http.NewServeMux()
	mux.HandleFunc("/api/hydro/worker/poll", b.HandlePoll)
	mux.HandleFunc("/api/hydro/worker/respond", b.HandleRespond)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	pluginJS := `module.exports = {
  view: async function (surface, item, req) {
    return { root: { type: "text", props: { text: "ok:" + surface + "/" + item + ":" + (req && req.screen ? req.screen.shape : "-") } } }
  }
}`
	bundle := WorkerBundle("com.t.e2e", pluginJS, srv.URL)

	dir := t.TempDir()
	bundlePath := filepath.Join(dir, "bundle.js")
	if err := os.WriteFile(bundlePath, bundle, 0o644); err != nil {
		t.Fatal(err)
	}
	marker := filepath.Join(dir, "marker.log")
	runnerPath := filepath.Join(dir, "runner.js")
	runner := `global.postMessage = function (m) {
  require("fs").appendFileSync(process.env.MARKER, JSON.stringify(m) + "\n");
};
const fs = require("fs");
const code = fs.readFileSync(process.argv[2], "utf8");
new Function(code)();
setInterval(function () {}, 1000);
`
	if err := os.WriteFile(runnerPath, []byte(runner), 0o644); err != nil {
		t.Fatal(err)
	}

	cmd := exec.Command(nodePath, runnerPath, bundlePath)
	cmd.Env = append(os.Environ(), "MARKER="+marker)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill() }()

	// Wait until the worker's session registers (first poll).
	t0 := time.Now()
	deadline := time.Now().Add(5 * time.Second)
	payload, _ := json.Marshal(map[string]any{
		"surface": "app", "item": "main",
		"req": Request{Trigger: TriggerOpen, Screen: Screen{Shape: "circle", Width: 466}},
	})
	var data []byte
	for time.Now().Before(deadline) {
		time.Sleep(200 * time.Millisecond)
		data, err = b.Call("com.t.e2e", "view", payload)
		if err != nil {
			continue // still offline -> bridge errors; retry
		}
		break
	}
	if err != nil {
		t.Fatalf("call with running node: %v", err)
	}
	t.Logf("call succeeded after %v", time.Since(t0))
	var v View
	if err := json.Unmarshal(data, &v); err != nil {
		t.Fatalf("view json: %v (%s)", err, data)
	}
	if v.Root == nil || v.Root.Props["text"] != "ok:app/main:circle" {
		t.Fatalf("view = %+v (raw %s)", v, data)
	}
	// The marker is written synchronously by the worker on its first poll
	// cycle (after an idle 300ms guard); wait for it while the process runs.
	deadline = time.Now().Add(3 * time.Second)
	var markerRaw []byte
	for time.Now().Before(deadline) {
		markerRaw, _ = os.ReadFile(marker)
		if strings.Contains(string(markerRaw), `"ready"`) {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !strings.Contains(string(markerRaw), `"ready"`) {
		t.Fatalf("worker never announced ready; marker: %s", markerRaw)
	}
	// Stop the worker so the httptest server can close without waiting on the
	// active long-poll connection.
	_ = cmd.Process.Kill()
	time.Sleep(200 * time.Millisecond)
}
