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

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/store"
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
// TestDeskBuddyPluginChain drives the shipped 桌搭 sources through the real
// chain: build -> install -> WasmBundle host in Node -> view/event. The side
// progress bars were removed from the plugin, so the tree must contain only
// centered text, the cover image and the blurred background.
func TestDeskBuddyPluginChain(t *testing.T) {
	nodePath, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not available")
	}
	cargoPath, err := exec.LookPath("cargo")
	if err != nil {
		t.Skip("cargo not available")
	}
	if out, err := exec.Command("rustup", "target", "list", "--installed").Output(); err != nil ||
		!strings.Contains(string(out), "wasm32-unknown-unknown") {
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
	wasmBytes, err := os.ReadFile(filepath.Join(pluginDir, "target", "wasm32-unknown-unknown",
		"release", "hydro_deskbuddy.wasm"))
	if err != nil {
		t.Fatalf("read deskbuddy wasm: %v", err)
	}
	manifest, err := os.ReadFile(filepath.Join(pluginDir, "manifest.json"))
	if err != nil {
		t.Fatalf("read manifest: %v", err)
	}

	ts, srv, st := startPluginServer(t, manifest, wasmBytes)
	defer ts.Close()
	_ = st

	bundle := hydrokit.WasmBundle("com.hydro.deskbuddy", "plugin.wasm", ts.URL)
	cmd, marker := runWorker(t, nodePath, string(bundle))
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
		code, b := call("/api/hydro/com.hydro.deskbuddy/app/main/view", "")
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

	// 没开歌词集成 -> 原版式（含 3s 刷新、不裁歌名）
	for _, want := range []string{
		`"refreshMs":3000`,
		`"backgroundColor":"#0b0d12"`,
		`"textAlign":"center"`,
		`"fontWeight":700`,
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("tree missing %s:\n%s", want, body)
		}
	}
	// 进度条功能已删除：tree must not carry box or progress nodes any more.
	if strings.Contains(body, `"box"`) || strings.Contains(body, "progress") {
		t.Fatalf("side bars/progress must be gone:\n%s", body)
	}

	// fontScale setting (WebUI) scales every text node: 时钟 21 -> 32
	if code, _ := call("/api/hydro/worker/store",
		`{"package":"com.hydro.deskbuddy","op":"set","key":"fontScale","value":1.5}`); code != http.StatusOK {
		t.Fatalf("store set fontScale -> %d", code)
	}
	time.Sleep(400 * time.Millisecond)
	if code, tuned := call("/api/hydro/com.hydro.deskbuddy/app/main/view", ""); code != http.StatusOK ||
		!strings.Contains(tuned, `"fontSize":54`) {
		t.Fatalf("fontScale not applied (%d):\n%s", code, tuned)
	}
	_, _ = call("/api/hydro/worker/store", `{"package":"com.hydro.deskbuddy","op":"clear"}`)

	// 打开 SPlayer 歌词集成（WebUI 写的就是这个键）：版式切到设计稿那套，
	// 刷新间隔跟着变成 0.5s。这里没有真的 SPlayer，所以只会有一条空歌词
	// ——插件仍要按新样式渲染。
	if code, _ := call("/api/hydro/worker/store",
		`{"package":"com.hydro.deskbuddy","op":"set","key":"splayerLyrics","value":true}`); code != http.StatusOK {
		t.Fatalf("store set splayerLyrics -> %d", code)
	}
	time.Sleep(400 * time.Millisecond)
	code, lyr := call("/api/hydro/com.hydro.deskbuddy/app/main/view", "")
	if code != http.StatusOK {
		t.Fatalf("lyrics view -> %d: %s", code, lyr)
	}
	for _, want := range []string{`"refreshMs":500`, `"textOverflow":"ellipsis"`, `"type":"row"`} {
		if !strings.Contains(lyr, want) {
			t.Fatalf("歌词版式缺少 %s:\n%s", want, lyr)
		}
	}
	_, _ = call("/api/hydro/worker/store", `{"package":"com.hydro.deskbuddy","op":"clear"}`)

	// cover tap -> effects executed host-side (music toggle + storage marker)
	if code, evt := call("/api/hydro/com.hydro.deskbuddy/app/main/event",
		`{"event":{"handler":"toggle","source":"tap"}}`); code != http.StatusOK {
		t.Fatalf("event -> %d: %s", code, evt)
	}
	if code, kv := call("/api/hydro/worker/store",
		`{"package":"com.hydro.deskbuddy","op":"get","key":"lastToggleAt"}`); code != http.StatusOK ||
		!strings.Contains(kv, `"found":true`) {
		t.Fatalf("storage effect was not executed by the host (%d): %s", code, kv)
	}

}

// TestShippedDeskBuddyPackage installs the COMMITTED .hpk (catching packaging
// drift) and drives it through the real chain.
func TestShippedDeskBuddyPackage(t *testing.T) {
	nodePath, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not available")
	}
	hpk, err := filepath.Abs(filepath.Join("..", "..", "examples", "com.hydro.deskbuddy.hpk"))
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
	if info.Runtime != "wasm" || info.ID != "com.hydro.deskbuddy" {
		t.Fatalf("shipped package manifest wrong: %+v", info)
	}

	// drift guard: the wasm inside the committed zip must match a fresh build
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
					packed, perr := readZipEntry(hpk, "plugin.wasm")
					if err == nil && perr == nil && !bytes.Equal(fresh, packed) {
						t.Fatalf("打包产物与源码不一致：请重新打包 com.hydro.deskbuddy.hpk（fresh %d vs packed %d bytes）",
							len(fresh), len(packed))
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
	cmd, marker := runWorker(t, nodePath, string(bundle))
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
		code, b := call("/api/hydro/com.hydro.deskbuddy/app/main/view", "")
		if code == http.StatusOK {
			body = b
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
	for _, want := range []string{`"refreshMs":3000`, `"backgroundColor":"#0b0d12"`, `"textAlign":"center"`} {
		if !strings.Contains(body, want) {
			t.Fatalf("shipped package tree missing %s:\n%s", want, body)
		}
	}
	if strings.Contains(body, `"box"`) || strings.Contains(body, "progress") {
		t.Fatalf("shipped package must not contain side bars/progress:\n%s", body)
	}

	rr := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/hydro/list", nil))
	if !strings.Contains(rr.Body.String(), "com.hydro.deskbuddy") {
		t.Fatalf("deskbuddy missing from plugin list: %s", rr.Body.String())
	}

	// the committed package ships a WebUI (web/index.html)
	wreq, _ := http.NewRequest(http.MethodGet, ts.URL+"/api/hydro/webui/com.hydro.deskbuddy/index.html", nil)
	wr := httptest.NewRecorder()
	srv.Handler().ServeHTTP(wr, wreq)
	if wr.Code != http.StatusOK || !strings.Contains(wr.Body.String(), "桌搭") {
		t.Fatalf("webui -> %d: %s", wr.Code, wr.Body.String())
	}
}

// startPluginServer boots an isolated server with the given plugin package
// installed, returning the httptest server, api server and store.
func startPluginServer(t *testing.T, manifest, wasmBytes []byte) (*httptest.Server, *api.Server, *store.Store) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	_, st, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	hpk := filepath.Join(t.TempDir(), "pkg.hpk")
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range map[string][]byte{"manifest.json": manifest, "plugin.wasm": wasmBytes} {
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
	if _, err := st.InstallPlugin(hpk); err != nil {
		t.Fatalf("install: %v", err)
	}
	srv, _, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer (2nd): %v", err)
	}
	return httptest.NewServer(srv.Handler()), srv, st
}

func runWorker(t *testing.T, nodePath, bundle string) (*exec.Cmd, string) {
	t.Helper()
	dir := t.TempDir()
	bundlePath := filepath.Join(dir, "bundle.js")
	if err := os.WriteFile(bundlePath, []byte(bundle), 0o644); err != nil {
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
	return cmd, marker
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
