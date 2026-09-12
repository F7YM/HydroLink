package app

import (
	"archive/zip"
	"bytes"
	"fmt"
	"io"
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

// TestDeskBuddyPluginChain runs the shipped 桌搭 (desk buddy) WASM plugin
// through the real chain: package the example sources, install, host the real
// WasmBundle in Node, then assert the produced tree (clock, arc progress bars
// for CPU/RAM, badges) and the playback-toggle effect.
func TestDeskBuddyPluginChain(t *testing.T) {
	nodePath, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not available")
	}
	cargoPath, err := exec.LookPath("cargo")
	if err != nil {
		t.Skip("cargo not available")
	}
	out, err := exec.Command("rustup", "target", "list", "--installed").Output()
	if err != nil || !strings.Contains(string(out), "wasm32-unknown-unknown") {
		t.Skip("rust wasm32-unknown-unknown target not installed")
	}
	pluginDir, err := filepath.Abs(filepath.Join("..", "..", "examples", "deskbuddy"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(pluginDir, "Cargo.toml")); err != nil {
		t.Skipf("deskbuddy source missing: %v", err)
	}

	build := exec.Command(cargoPath, "build", "--release", "--target", "wasm32-unknown-unknown")
	build.Dir = pluginDir
	var buildLog bytes.Buffer
	build.Stdout, build.Stderr = &buildLog, &buildLog
	if err := build.Run(); err != nil {
		t.Fatalf("cargo build deskbuddy: %v\n%s", err, buildLog.String())
	}
	wasmBytes, err := os.ReadFile(filepath.Join(pluginDir, "target", "wasm32-unknown-unknown", "release", "hydro_deskbuddy.wasm"))
	if err != nil {
		t.Fatalf("read deskbuddy wasm: %v", err)
	}
	manifest, err := os.ReadFile(filepath.Join(pluginDir, "manifest.json"))
	if err != nil {
		t.Fatalf("read manifest: %v", err)
	}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	_, st, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	hpk := filepath.Join(t.TempDir(), "deskbuddy.hpk")
	{
		var buf bytes.Buffer
		zw := zip.NewWriter(&buf)
		for name, content := range map[string][]byte{
			"manifest.json": manifest,
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
		t.Fatalf("install deskbuddy: %v", err)
	}
	srv, _, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer (2nd): %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	bundle := hydrokit.WasmBundle("com.demo.deskbuddy", "plugin.wasm", ts.URL)
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

	call := func(path, body string) (int, string) {
		var rdr *strings.Reader
		if body == "" {
			rdr = strings.NewReader("")
		} else {
			rdr = strings.NewReader(body)
		}
		req, _ := http.NewRequest(http.MethodPost, ts.URL+path, rdr)
		rr := httptest.NewRecorder()
		srv.Handler().ServeHTTP(rr, req)
		return rr.Code, rr.Body.String()
	}

	var body string
	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		code, b := call("/api/hydro/com.demo.deskbuddy/app/main/view", "")
		if code == http.StatusOK {
			body = b
			break
		}
		if raw := mustRead(marker); strings.Contains(raw, `"error"`) {
			t.Fatalf("deskbuddy worker error: %s", raw)
		}
		time.Sleep(300 * time.Millisecond)
	}
	if body == "" {
		t.Fatalf("deskbuddy view never returned 200; marker: %s", mustRead(marker))
	}
	// Default side bars are boxes; opt into arc mode to exercise <progress>.
	if code, _ := call("/api/hydro/worker/store",
		`{"package":"com.demo.deskbuddy","op":"set","key":"bars","value":"curve"}`); code != http.StatusOK {
		t.Fatalf("enable arc mode -> %d", code)
	}
	time.Sleep(400 * time.Millisecond) // let the worker re-enter the poll
	if code, b := call("/api/hydro/com.demo.deskbuddy/app/main/view", ""); code == http.StatusOK {
		body = b
	} else {
		t.Fatalf("arc-mode view -> %d: %s", code, b)
	}

	for _, want := range []string{
		`"refreshMs":3000`,
		`"marginLeft"`, // box-traced curve rows
		`"backgroundColor":"#0b0d12"`,
		`"textAlign":"center"`,
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("tree missing %s:\n%s", want, body)
		}
	}
	if strings.Contains(body, "progress") {
		t.Fatalf("progress component must not be used:\n%s", body)
	}

	// Default mode (no settings) renders box bars, not progress nodes.
	_, _ = call("/api/hydro/worker/store", `{"package":"com.demo.deskbuddy","op":"clear"}`)
	time.Sleep(400 * time.Millisecond)
	codeDef, defTree := call("/api/hydro/com.demo.deskbuddy/app/main/view", "")
	if codeDef != http.StatusOK {
		t.Fatalf("default view -> %d: %s", codeDef, defTree)
	}
	if strings.Count(defTree, `"type":"box"`) != 2 || strings.Contains(defTree, `"marginLeft"`) {
		t.Fatalf("default mode should render 2 flow bar fills and no arcs:\n%s", defTree)
	}
	if !strings.Contains(defTree, `"justifyContent":"flex-end"`) {
		t.Fatalf("bars must be bottom-aligned flow columns:\n%s", defTree)
	}
	_, _ = call("/api/hydro/worker/store",
		`{"package":"com.demo.deskbuddy","op":"set","key":"bars","value":"curve"}`)
	time.Sleep(400 * time.Millisecond)

	// Settings from the plugin's server-scope storage drive the geometry and
	// background (the WebUI writes these keys): edgeGap 40 -> radius shrinks.
	if code, _ := call("/api/hydro/worker/store",
		`{"package":"com.demo.deskbuddy","op":"set","key":"edgeGap","value":40}`); code != http.StatusOK {
		t.Fatalf("store set edgeGap -> %d", code)
	}
	if code, _ := call("/api/hydro/worker/store",
		`{"package":"com.demo.deskbuddy","op":"set","key":"colors","value":"split"}`); code != http.StatusOK {
		t.Fatalf("store set colors -> %d", code)
	}
	time.Sleep(400 * time.Millisecond) // let the worker re-enter the poll
	code, tuned := call("/api/hydro/com.demo.deskbuddy/app/main/view?shape=circle&width=466&height=466&dpr=2&dt=watch", "")
	if code != http.StatusOK {
		t.Fatalf("tuned view -> %d: %s", code, tuned)
	}
	// colors=split must tint the two fills differently (curve rows use bg color)
	if !strings.Contains(tuned, `"backgroundColor":"#BAC3FF"`) || !strings.Contains(tuned, `"backgroundColor":"#7ADFA0"`) {
		t.Fatalf("colors=split not applied:\n%s", tuned)
	}
	// edgeGap shapes the curve: rows exist with per-row offsets
	if strings.Count(tuned, `"marginLeft"`) < 20 {
		t.Fatalf("curve rows missing offset geometry:\n%s", tuned)
	}
	// back to defaults (straight bars)
	_, _ = call("/api/hydro/worker/store", `{"package":"com.demo.deskbuddy","op":"clear"}`)
	time.Sleep(400 * time.Millisecond)

	// Geometry adapts to the reported screen: the px unit scales by
	// designWidth/screenWidth (480/466), so the arc keeps a ~16 physical px
	// gap to the edge: radius = 240 - 16*480/466 ≈ 224 design px.
	code, scaled := call("/api/hydro/com.demo.deskbuddy/app/main/view?shape=circle&width=466&height=466&dpr=2&dt=watch", "")
	if code != http.StatusOK {
		t.Fatalf("scaled view -> %d: %s", code, scaled)
	}
	if !strings.Contains(scaled, `"radius":224`) {
		t.Fatalf("arc geometry not screen-adapted (want radius 224 for 466px):\n%s", scaled)
	}
	// 466 physical px maps to a 480x480 design canvas (designWidth 480), i.e.
	// the canvas covers the whole screen.
	if !strings.Contains(scaled, `"width":480`) || !strings.Contains(scaled, `"height":480`) {
		t.Fatalf("arc canvas not screen-adapted (want 480x480 design px):\n%s", scaled)
	}

	// Cover tap -> effects executed host-side (music toggle + a server-scope
	// storage write). Effects are internal to the runtime and are NOT echoed
	// back in the HTTP view, so verify through the storage they produced.
	code, evt := call("/api/hydro/com.demo.deskbuddy/app/main/event",
		`{"event":{"handler":"toggle","source":"tap"}}`)
	if code != http.StatusOK {
		t.Fatalf("event -> %d: %s", code, evt)
	}
	kvBody := `{"package":"com.demo.deskbuddy","op":"get","key":"lastToggleAt"}`
	code, kv := call("/api/hydro/worker/store", kvBody)
	if code != http.StatusOK {
		t.Fatalf("store get -> %d: %s", code, kv)
	}
	if !strings.Contains(kv, `"found":true`) {
		t.Fatalf("storage effect was not executed by the host: %s", kv)
	}
}

// TestShippedDeskBuddyPackage installs the COMMITTED .hpk artifact (not a
// freshly built one) and drives it through the real chain, so packaging drift
// (e.g. a stale plugin.wasm inside the zip) fails the suite.
func TestShippedDeskBuddyPackage(t *testing.T) {
	nodePath, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not available")
	}
	hpk, err := filepath.Abs(filepath.Join("..", "..", "examples", "com.demo.deskbuddy.hpk"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(hpk); err != nil {
		t.Skipf("shipped package missing: %v", err)
	}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	_, st, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	info, err := st.InstallPlugin(hpk)
	if err != nil {
		t.Fatalf("install shipped package: %v", err)
	}
	if info.Runtime != "wasm" || info.ID != "com.demo.deskbuddy" {
		t.Fatalf("shipped package manifest wrong: %+v", info)
	}

	// Packaging drift guard: the wasm inside the committed .hpk must match a
	// fresh build of the source tree (skipped when the Rust toolchain or the
	// wasm target is unavailable).
	if cargoPath, err := exec.LookPath("cargo"); err == nil {
		if out, err := exec.Command("rustup", "target", "list", "--installed").Output(); err == nil &&
			strings.Contains(string(out), "wasm32-unknown-unknown") {
			pluginDir, err := filepath.Abs(filepath.Join("..", "..", "examples", "deskbuddy"))
			if err == nil {
				build := exec.Command(cargoPath, "build", "--release", "--target", "wasm32-unknown-unknown")
				build.Dir = pluginDir
				if err := build.Run(); err == nil {
					fresh, err := os.ReadFile(filepath.Join(pluginDir, "target", "wasm32-unknown-unknown",
						"release", "hydro_deskbuddy.wasm"))
					if err == nil {
						packed, err := readZipEntry(hpk, "plugin.wasm")
						if err != nil {
							t.Fatalf("read plugin.wasm from hpk: %v", err)
						}
						if !bytes.Equal(fresh, packed) {
							t.Fatalf("打包产物与源码不一致：请重新打包 com.demo.deskbuddy.hpk"+
								"（fresh %d bytes vs packed %d bytes）", len(fresh), len(packed))
						}
					}
				}
			}
		}
	}

	srv, _, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer (2nd): %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	bundle := hydrokit.WasmBundle(info.ID, info.Entry, ts.URL)
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

	call := func(path, body string) (int, string) {
		req, _ := http.NewRequest(http.MethodPost, ts.URL+path, strings.NewReader(body))
		rr := httptest.NewRecorder()
		srv.Handler().ServeHTTP(rr, req)
		return rr.Code, rr.Body.String()
	}

	var body string
	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		req, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/hydro/com.demo.deskbuddy/app/main/view", nil)
		rr := httptest.NewRecorder()
		srv.Handler().ServeHTTP(rr, req)
		if rr.Code == http.StatusOK {
			body = rr.Body.String()
			break
		}
		if raw := mustRead(marker); strings.Contains(raw, `"error"`) {
			t.Fatalf("shipped package worker error: %s", raw)
		}
		time.Sleep(300 * time.Millisecond)
	}
	if body == "" {
		t.Fatalf("shipped package view never returned 200; marker: %s", mustRead(marker))
	}
	for _, want := range []string{`"refreshMs":3000`, `"backgroundColor":"#0b0d12"`, `"type":"box"`} {
		if !strings.Contains(body, want) {
			t.Fatalf("shipped package tree missing %s:\n%s", want, body)
		}
	}
	if strings.Count(body, `"type":"box"`) != 2 {
		t.Fatalf("shipped package should default to 2 flow bar fills:\n%s", body)
	}
	if code, _ := call("/api/hydro/worker/store",
		`{"package":"com.demo.deskbuddy","op":"set","key":"bars","value":"curve"}`); code != http.StatusOK {
		t.Fatalf("enable arc mode -> %d", code)
	}
	time.Sleep(400 * time.Millisecond)
	if code, curveBody := call("/api/hydro/com.demo.deskbuddy/app/main/view", ""); code != http.StatusOK ||
		strings.Count(curveBody, `"marginLeft"`) < 20 {
		t.Fatalf("curve opt-in failed (%d):\n%s", code, curveBody)
	}
	// The listed plugins must expose the wasm runtime + its declared permissions.
	rr := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/hydro/list", nil))
	if !strings.Contains(rr.Body.String(), "com.demo.deskbuddy") {
		t.Fatalf("deskbuddy missing from plugin list: %s", rr.Body.String())
	}
}

// readZipEntry returns one file's bytes from a zip archive.
func readZipEntry(zipPath, name string) ([]byte, error) {
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return nil, err
	}
	defer zr.Close()
	for _, f := range zr.File {
		if f.Name != name {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		defer rc.Close()
		return io.ReadAll(rc)
	}
	return nil, fmt.Errorf("entry %s not found", name)
}
