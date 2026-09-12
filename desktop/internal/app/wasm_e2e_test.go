package app

import (
	"archive/zip"
	"bytes"
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

// TestWasmPluginProductionChain covers the ABI v1 path end to end: build the
// Rust fixture to wasm, install it as a runtime:"wasm" package, then let a Node
// process run the REAL WasmBundle worker against the real server (context
// endpoint -> hydro_view -> respond).
func TestWasmPluginProductionChain(t *testing.T) {
	nodePath, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not available")
	}
	cargoPath, err := exec.LookPath("cargo")
	if err != nil {
		t.Skip("cargo not available")
	}
	// The wasm target is required to build the fixture.
	out, err := exec.Command("rustup", "target", "list", "--installed").Output()
	if err != nil || !strings.Contains(string(out), "wasm32-unknown-unknown") {
		t.Skip("rust wasm32-unknown-unknown target not installed")
	}

	fixtureDir, err := filepath.Abs(filepath.Join("..", "..", "examples", "wasm-fixture"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(fixtureDir, "Cargo.toml")); err != nil {
		t.Skipf("fixture missing: %v", err)
	}

	build := exec.Command(cargoPath, "build", "--release", "--target", "wasm32-unknown-unknown")
	build.Dir = fixtureDir
	var buildLog bytes.Buffer
	build.Stdout = &buildLog
	build.Stderr = &buildLog
	if err := build.Run(); err != nil {
		t.Fatalf("cargo build fixture: %v\n%s", err, buildLog.String())
	}
	wasmPath := filepath.Join(fixtureDir, "target", "wasm32-unknown-unknown", "release", "hydro_wasm_fixture.wasm")
	wasmBytes, err := os.ReadFile(wasmPath)
	if err != nil {
		t.Fatalf("read fixture wasm: %v", err)
	}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	_, st, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}

	manifest := `{
  "id": "com.test.wasmfix",
  "name": "WASM 夹具",
  "version": "0.1.0",
  "runtime": "wasm",
  "entry": "plugin.wasm",
  "apps": [{"id":"main","name":"主页"}],
  "permissions": ["system"]
}`
	hpk := filepath.Join(t.TempDir(), "wasmfix.hpk")
	{
		var buf bytes.Buffer
		zw := zip.NewWriter(&buf)
		for name, content := range map[string][]byte{
			"manifest.json": []byte(manifest),
			"plugin.wasm":   wasmBytes,
		} {
			w, err := zw.Create(name)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := w.Write(content); err != nil {
				t.Fatal(err)
			}
		}
		_ = zw.Close()
		if err := os.WriteFile(hpk, buf.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := st.InstallPlugin(hpk); err != nil {
		t.Fatalf("install wasm plugin: %v", err)
	}

	// Boot again so the plugin registers, then serve it.
	srv, _, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer (2nd): %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	bundle := hydrokit.WasmBundle("com.test.wasmfix", "plugin.wasm", ts.URL)

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

	// The worker registers its session on the first poll; a view request then
	// gets delivered. (The "ready" message only fires once a poll returns, so
	// drive the flow instead of waiting for it.)
	viewURL := ts.URL + "/api/hydro/com.test.wasmfix/app/main/view"
	var body string
	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		req, _ := http.NewRequest(http.MethodPost, viewURL, nil)
		rr := httptest.NewRecorder()
		srv.Handler().ServeHTTP(rr, req)
		if rr.Code == http.StatusOK {
			body = rr.Body.String()
			break
		}
		if raw := mustRead(marker); strings.Contains(raw, `"error"`) {
			t.Fatalf("wasm worker error: %s", raw)
		}
		time.Sleep(300 * time.Millisecond)
	}
	if body == "" {
		t.Fatalf("wasm plugin view never returned 200; marker: %s", mustRead(marker))
	}
	if !strings.Contains(body, "fixture app/main") {
		t.Fatalf("unexpected tree: %s", body)
	}
	if !strings.Contains(body, "sys=true") {
		t.Fatalf("context missing system metrics (permission system): %s", body)
	}
}

func mustRead(path string) string {
	b, _ := os.ReadFile(path)
	return string(b)
}
