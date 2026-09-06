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
// XDG_CONFIG_HOME), installs a plugin package, starts a Node process running
// the REAL WorkerBundle against the real server, and drives a view through it.
// This is the production-equivalent chain minus the webview host.
func TestJSRuntimeProductionChain(t *testing.T) {
	nodePath, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not available")
	}
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())

	_, st, err := NewServer() // first boot: plugin not installed yet
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}

	// Install the greeting plugin (same layout as examples/com.example.greeting).
	pluginJS := `module.exports = {
  manifest() { return { package: { id: 'com.example.greeting', name: '示例问候', version: '0.1.0' }, apps: [{ id: 'main', name: '问候' }], widgets: [] } },
  async view(surface, item, req) {
    if (surface !== 'app' || item !== 'main') return null
    const name = (await kit.storage.getItem('name')) || '世界'
    return {
      root: {
        type: 'column', style: { padding: 16 },
        children: [
          { type: 'text', props: { text: '你好，' + name + '！' }, style: { fontSize: 30, color: '#FFFFFF' } },
          { type: 'input', props: { placeholder: '输入名字', value: name }, events: { change: 'setname' } }
        ]
      }
    }
  }
}`
	manifest := `{
  "id": "com.example.greeting",
  "name": "示例问候",
  "version": "0.1.0",
  "entry": "server.js",
  "apps": [{"id":"main","name":"问候"}],
  "widgets": [],
  "permissions": ["storage"]
}`
	hpk := filepath.Join(t.TempDir(), "com.example.greeting.hpk")
	{
		var buf bytes.Buffer
		zw := zip.NewWriter(&buf)
		for name, content := range map[string]string{"manifest.json": manifest, "server.js": pluginJS} {
			w, _ := zw.Create(name)
			_, _ = w.Write([]byte(content))
		}
		_ = zw.Close()
		if err := os.WriteFile(hpk, buf.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := st.InstallPlugin(hpk); err != nil {
		t.Fatalf("install: %v", err)
	}

	// NewServer ran before the install, so (like a real app) boot again to
	// register the newly installed plugin from disk.
	srv2, _, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer (2nd): %v", err)
	}
	ts := httptest.NewServer(srv2.Handler())
	defer ts.Close()

	// Read the entry and build the real bundle, as GetPluginBundle does.
	code, err := st.ReadPluginFile("com.example.greeting", "server.js")
	if err != nil {
		t.Fatalf("read entry: %v", err)
	}
	bundle := hydrokit.WorkerBundle("com.example.greeting", string(code), ts.URL)

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
new Function(require("fs").readFileSync(process.argv[2], "utf8"))();
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

	// The worker's first poll registers instantly; the ready marker only
	// appears after the first idle window, so skip waiting for it.
	_ = marker

	viewURL := ts.URL + "/api/hydro/com.example.greeting/app/main/view?shape=circle&width=466&dpr=2&dt=watch"
	deadline := time.Now().Add(15 * time.Second)
	var body string
	for time.Now().Before(deadline) {
		rr := httptest.NewRecorder()
		// use real client through the handler
		req, _ := http.NewRequest(http.MethodPost, viewURL, nil)
		srv2.Handler().ServeHTTP(rr, req)
		if rr.Code == http.StatusOK {
			body = rr.Body.String()
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if body == "" {
		t.Fatal("plugin view never returned 200")
	}
	var v struct {
		Root struct {
			Children []struct {
				Props map[string]any `json:"props"`
			} `json:"children"`
		} `json:"root"`
	}
	if err := json.Unmarshal([]byte(body), &v); err != nil {
		t.Fatalf("view json: %v (%s)", err, body)
	}
	first := ""
	if len(v.Root.Children) > 0 {
		first, _ = v.Root.Children[0].Props["text"].(string)
	}
	if first != "你好，世界！" {
		t.Fatalf("first text = %q (want 你好，世界！) body=%s", first, body)
	}

	// Storage through the kit endpoint: set name, then re-view.
	storeURL := ts.URL + "/api/hydro/worker/store"
	setBody := `{"package":"com.example.greeting","op":"set","key":"name","value":"上海"}`
	req, _ := http.NewRequest(http.MethodPost, storeURL, bytes.NewBufferString(setBody))
	rr := httptest.NewRecorder()
	srv2.Handler().ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("store set -> %d: %s", rr.Code, rr.Body.String())
	}
	time.Sleep(400 * time.Millisecond) // let the worker re-enter the poll
	req2, _ := http.NewRequest(http.MethodPost, viewURL, nil)
	rr2 := httptest.NewRecorder()
	srv2.Handler().ServeHTTP(rr2, req2)
	if rr2.Code != http.StatusOK || !strings.Contains(rr2.Body.String(), "上海") {
		t.Fatalf("view after store -> %d: %s", rr2.Code, rr2.Body.String())
	}
}
