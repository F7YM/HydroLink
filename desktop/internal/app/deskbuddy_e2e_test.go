package app

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net"
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

// runJsWorker 用 node 跑一遍真实的 WorkerBundle（JS 插件的宿主胶水）。
func runJsWorker(t *testing.T, nodePath, bundle string) {
	t.Helper()
	dir := t.TempDir()
	bundlePath := filepath.Join(dir, "bundle.js")
	if err := os.WriteFile(bundlePath, []byte(bundle), 0o644); err != nil {
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
	t.Cleanup(func() { _ = cmd.Process.Kill() })
}

// packDeskbuddy 把 examples/deskbuddy 打成 .hpk（和发行流程一致）。
func packDeskbuddy(t *testing.T, dir, out string) {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	names := []string{"manifest.json", "server.js", "web/index.html"}
	for _, n := range names {
		data, err := os.ReadFile(filepath.Join(dir, n))
		if err != nil {
			t.Fatalf("read %s: %v", n, err)
		}
		w, err := zw.Create(n)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = w.Write(data)
	}
	_ = zw.Close()
	if err := os.WriteFile(out, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
}

// deskbuddyFixture：打好包、装好、起好服务、node 里跑起真实胶水之后的最小夹具。
type deskbuddyFixture struct {
	call  func(path, body string) (int, string)
	store func(t *testing.T, body string)
	view  func() (int, string)
}

// setupDeskbuddy 把 examples/deskbuddy 打包安装并跑起来（view 能返回 200）。
func setupDeskbuddy(t *testing.T) *deskbuddyFixture {
	t.Helper()
	nodePath, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not available")
	}
	pluginDir, err := filepath.Abs(filepath.Join("..", "..", "examples", "deskbuddy"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(pluginDir, "server.js")); err != nil {
		t.Skipf("deskbuddy source missing: %v", err)
	}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	_, st, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	hpk := filepath.Join(t.TempDir(), "deskbuddy.hpk")
	packDeskbuddy(t, pluginDir, hpk)
	info, err := st.InstallPlugin(hpk)
	if err != nil {
		t.Fatalf("install: %v", err)
	}
	if info.Runtime != "js" || info.ID != "com.hydro.deskbuddy" {
		t.Fatalf("桌搭应当是 JS 插件: %+v", info)
	}

	srv, _, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer (2nd): %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)

	code, err := st.ReadPluginFile("com.hydro.deskbuddy", "server.js")
	if err != nil {
		t.Fatalf("read entry: %v", err)
	}
	runJsWorker(t, nodePath, string(hydrokit.WorkerBundle("com.hydro.deskbuddy", string(code), ts.URL)))

	f := &deskbuddyFixture{}
	f.call = func(path, body string) (int, string) {
		req, _ := http.NewRequest(http.MethodPost, ts.URL+path, strings.NewReader(body))
		rr := httptest.NewRecorder()
		srv.Handler().ServeHTTP(rr, req)
		return rr.Code, rr.Body.String()
	}
	f.view = func() (int, string) {
		return f.call("/api/hydro/com.hydro.deskbuddy/app/main/view?shape=circle&width=466&height=466", "")
	}
	f.store = func(t *testing.T, body string) {
		t.Helper()
		if code, out := f.call("/api/hydro/worker/store", body); code != http.StatusOK {
			t.Fatalf("store %s -> %d: %s", body, code, out)
		}
	}
	// 插件 worker 起来需要一点时间：等到第一次 view 返回 200
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if code, _ := f.view(); code == http.StatusOK {
			return f
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatal("deskbuddy view 始终没有 200")
	return nil
}

// storeValue 读插件自己 server 作用域存储里的某个键。
func (f *deskbuddyFixture) storeValue(t *testing.T, key string) (map[string]any, bool) {
	t.Helper()
	code, body := f.call("/api/hydro/worker/store",
		`{"package":"com.hydro.deskbuddy","op":"get","key":"`+key+`"}`)
	if code != http.StatusOK {
		t.Fatalf("store get %s -> %d: %s", key, code, body)
	}
	var out struct {
		Found bool           `json:"found"`
		Value map[string]any `json:"value"`
	}
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatal(err)
	}
	return out.Value, out.Found
}

// TestDeskBuddyPluginChain 驱动桌搭插件源码走完整链路：
// 打包 → 安装 → node 里跑真实胶水 → view，验证两套版式与外观设置。
//
// 桌搭是 JS 插件：网络（SPlayer 歌词）由它自己用 kit.ws 连，宿主不认识 SPlayer。
func TestDeskBuddyPluginChain(t *testing.T) {
	f := setupDeskbuddy(t)

	_, body := f.view()

	// 没开歌词集成 → 原版式（3s 刷新、居中大字、没有内容行容器）
	for _, want := range []string{`"refreshMs":3000`, `"backgroundColor":"#0b0d12"`, `"textAlign":"center"`} {
		if !strings.Contains(body, want) {
			t.Fatalf("原版式缺少 %s:\n%s", want, body)
		}
	}
	if strings.Contains(body, `"type":"row"`) {
		t.Fatalf("原版式不该有内容行容器:\n%s", body)
	}
	// 封面可点（切播放/暂停由插件自己调 kit.music.action）
	if !strings.Contains(body, `"tap":"toggle"`) {
		t.Fatalf("封面上没有点击事件:\n%s", body)
	}

	// 外观设置照旧生效：fontScale 1.5 → 时钟 36 -> 54
	f.store(t, `{"package":"com.hydro.deskbuddy","op":"set","key":"fontScale","value":1.5}`)
	time.Sleep(300 * time.Millisecond)
	if code, tuned := f.view(); code != http.StatusOK || !strings.Contains(tuned, `"fontSize":54`) {
		t.Fatalf("fontScale 没生效 (%d):\n%s", code, tuned)
	}
	f.store(t, `{"package":"com.hydro.deskbuddy","op":"clear"}`)

	// 打开歌词集成 → 设计稿版式（0.5s 刷新、内容行、单行省略号）。
	// 端口指到一个没人监听的端口：这条用例不该去连真在跑的 SPlayer。
	f.store(t, `{"package":"com.hydro.deskbuddy","op":"set","key":"splayerLyrics","value":true}`)
	f.store(t, `{"package":"com.hydro.deskbuddy","op":"set","key":"splayerPort","value":9}`)
	time.Sleep(300 * time.Millisecond)
	code2, lyr := f.view()
	if code2 != http.StatusOK {
		t.Fatalf("歌词版式 view -> %d: %s", code2, lyr)
	}
	for _, want := range []string{`"refreshMs":500`, `"type":"row"`, `"textOverflow":"ellipsis"`} {
		if !strings.Contains(lyr, want) {
			t.Fatalf("歌词版式缺少 %s:\n%s", want, lyr)
		}
	}
	// 版面必须落在容器的内容盒里（46…424）：越界会被硬裁，省略号一起没
	var tree struct {
		Root struct {
			Children []struct {
				Type  string         `json:"type"`
				Style map[string]any `json:"style"`
			} `json:"children"`
		} `json:"root"`
	}
	if err := json.Unmarshal([]byte(lyr), &tree); err != nil {
		t.Fatal(err)
	}
	var row map[string]any
	for _, c := range tree.Root.Children {
		if c.Type == "row" {
			row = c.Style
		}
	}
	if row == nil {
		t.Fatal("歌词版式里没有内容行")
	}
	left, _ := row["left"].(float64)
	width, _ := row["width"].(float64)
	if left+42 != 46 {
		t.Fatalf("封面没有落在设计稿的 46：left=%v（内容盒坐标）", left)
	}
	if left+42+width != 424 {
		t.Fatalf("内容行右端没有贴住安全区右缘 424：%v+%v", left, width)
	}
}

// TestDeskBuddyConnectsToSPlayer：本机真的跑着 SPlayer 时，验证插件用 kit.ws
// 直连它、并把状态写进自己的存储 —— 通用网络能力最关键的一段。
func TestDeskBuddyConnectsToSPlayer(t *testing.T) {
	conn, err := net.DialTimeout("tcp", "127.0.0.1:25885", time.Second)
	if err != nil {
		t.Skipf("本机 25885 没有服务在监听（SPlayer 没开就跳过）: %v", err)
	}
	_ = conn.Close()
	f := setupDeskbuddy(t)

	f.store(t, `{"package":"com.hydro.deskbuddy","op":"set","key":"splayerLyrics","value":true}`)
	f.store(t, `{"package":"com.hydro.deskbuddy","op":"set","key":"splayerPort","value":25885}`)

	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		_, _ = f.view() // 插件只在有请求时才会跑
		if st, found := f.storeValue(t, "splayerState"); found && st["connected"] == true {
			return // 连上了，收工
		}
		time.Sleep(500 * time.Millisecond)
	}
	st, found := f.storeValue(t, "splayerState")
	t.Fatalf("插件没能连上本机的 SPlayer（状态 %v，found=%v）", st, found)
}

// TestShippedDeskBuddyPackage 装提交好的 .hpk（防打包漂移），并跑一遍真实链路。
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
	srcDir, err := filepath.Abs(filepath.Join("..", "..", "examples", "deskbuddy"))
	if err != nil {
		t.Fatal(err)
	}

	// 漂移守卫：包里的 server.js 必须和源码一致
	fresh, err := os.ReadFile(filepath.Join(srcDir, "server.js"))
	if err != nil {
		t.Fatal(err)
	}
	packed, err := readZipEntry(hpk, "server.js")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(fresh, packed) {
		t.Fatalf("打包产物与源码不一致：请重新打包 com.hydro.deskbuddy.hpk（源码 %d vs 包内 %d 字节）",
			len(fresh), len(packed))
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
	if info.Runtime != "js" || info.ID != "com.hydro.deskbuddy" {
		t.Fatalf("shipped package manifest wrong: %+v", info)
	}

	srv, _, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer (2nd): %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	code, err := st.ReadPluginFile("com.hydro.deskbuddy", "server.js")
	if err != nil {
		t.Fatal(err)
	}
	runJsWorker(t, nodePath, string(hydrokit.WorkerBundle("com.hydro.deskbuddy", string(code), ts.URL)))

	call := func(path string) (int, string) {
		rr := httptest.NewRecorder()
		srv.Handler().ServeHTTP(rr, httptest.NewRequest(http.MethodPost, path, nil))
		return rr.Code, rr.Body.String()
	}
	var body string
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if c, b := call("/api/hydro/com.hydro.deskbuddy/app/main/view?shape=circle&width=466&height=466"); c == http.StatusOK {
			body = b
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if body == "" {
		t.Fatal("shipped package view never returned 200")
	}
	for _, want := range []string{`"refreshMs":3000`, `"backgroundColor":"#0b0d12"`, `"textAlign":"center"`} {
		if !strings.Contains(body, want) {
			t.Fatalf("shipped package tree missing %s:\n%s", want, body)
		}
	}

	rr := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/hydro/list", nil))
	if !strings.Contains(rr.Body.String(), "com.hydro.deskbuddy") {
		t.Fatalf("deskbuddy missing from plugin list: %s", rr.Body.String())
	}

	// 包里带着 WebUI
	wr := httptest.NewRecorder()
	srv.Handler().ServeHTTP(wr, httptest.NewRequest(http.MethodGet,
		"/api/hydro/webui/com.hydro.deskbuddy/index.html", nil))
	if wr.Code != http.StatusOK || !strings.Contains(wr.Body.String(), "桌搭") {
		t.Fatalf("webui -> %d: %s", wr.Code, wr.Body.String())
	}
}
