package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"hydrolink-desktop/internal/store"
)

// 桌面 Compose 端自实现业务后需要的管理端点：设备列表/删除、日志、命令增删改、设置读写。
// 这一组测试钉住它们的形状与语义（尤其「端口只存配置、不假装能热改」）。

func uiTestServer(t *testing.T) *Server {
	t.Helper()
	// 这组端点都要读写 store，必须给一个真的 store（New(nil) 的 store 是 nil）
	srv := New(store.NewIn(t.TempDir()))
	srv.AddLog("第一条日志", "info")
	srv.AddLog("出错了", "error")
	return srv
}

func doJSON(t *testing.T, srv *Server, method, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var req *http.Request
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		req = httptest.NewRequest(method, path, bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
	} else {
		req = httptest.NewRequest(method, path, nil)
	}
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	return rec
}

func contains(haystack, needle string) bool { return strings.Contains(haystack, needle) }

func TestGetLogs(t *testing.T) {
	srv := uiTestServer(t)
	rec := doJSON(t, srv, http.MethodGet, "/api/logs", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/logs = %d, want 200", rec.Code)
	}
	var body struct {
		Logs []LogEntry `json:"logs"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v (%s)", err, rec.Body.String())
	}
	if len(body.Logs) != 2 {
		t.Fatalf("logs = %d, want 2: %s", len(body.Logs), rec.Body.String())
	}
	// 级别要原样带给 UI（安卓端日志页按 error/warning/success 上色）
	if body.Logs[1].Level != "error" {
		t.Fatalf("last log level = %q, want error", body.Logs[1].Level)
	}
}

func TestDevicesListAndDelete(t *testing.T) {
	srv := uiTestServer(t)
	if err := srv.store.AddTrustedDevice("dev-1", "手表 A"); err != nil {
		t.Fatal(err)
	}

	rec := doJSON(t, srv, http.MethodGet, "/api/devices", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/devices = %d, want 200", rec.Code)
	}
	if !contains(rec.Body.String(), "手表 A") {
		t.Fatalf("devices body missing device: %s", rec.Body.String())
	}

	rec = doJSON(t, srv, http.MethodDelete, "/api/devices/dev-1", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("DELETE /api/devices/dev-1 = %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	if srv.store.IsTrusted("dev-1") {
		t.Fatal("device still trusted after delete")
	}
}

func TestCommandCRUD(t *testing.T) {
	srv := uiTestServer(t)

	rec := doJSON(t, srv, http.MethodPost, "/api/commands", map[string]string{
		"name": "重启服务", "command": "systemctl restart hydrolink",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("POST /api/commands = %d, want 200 (%s)", rec.Code, rec.Body.String())
	}

	rec = doJSON(t, srv, http.MethodPost, "/api/commands", map[string]string{"name": "  "})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("POST 空命令名 = %d, want 400", rec.Code)
	}

	rec = doJSON(t, srv, http.MethodPut, "/api/commands/0", map[string]string{
		"name": "重启服务", "command": "systemctl restart hydrolink2",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("PUT /api/commands/0 = %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	commands, _ := srv.store.LoadCommands()
	if len(commands) != 1 || commands[0].Command != "systemctl restart hydrolink2" {
		t.Fatalf("commands = %+v, want the updated one", commands)
	}

	rec = doJSON(t, srv, http.MethodDelete, "/api/commands/5", nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("DELETE 越界 = %d, want 404", rec.Code)
	}

	rec = doJSON(t, srv, http.MethodDelete, "/api/commands/0", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("DELETE /api/commands/0 = %d, want 200", rec.Code)
	}
	commands, _ = srv.store.LoadCommands()
	if len(commands) != 0 {
		t.Fatalf("commands = %+v, want empty", commands)
	}
}

func TestSettingsRoundTrip(t *testing.T) {
	srv := uiTestServer(t)

	rec := doJSON(t, srv, http.MethodPut, "/api/settings", map[string]any{
		"port": 9100, "accept_all": true, "market_source": "auto",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("PUT /api/settings = %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	var view settingsView
	if err := json.Unmarshal(rec.Body.Bytes(), &view); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if view.Port != 9100 || !view.AcceptAll || view.MarketSource != "auto" {
		t.Fatalf("settings = %+v, want port 9100 / accept_all / auto", view)
	}
	// 端口越界要拒：否则配置文件会被写坏
	rec = doJSON(t, srv, http.MethodPut, "/api/settings", map[string]any{"port": 70000})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("PUT 越界端口 = %d, want 400", rec.Code)
	}
	cfg, _ := srv.store.LoadConfig()
	if cfg.LastPort != 9100 {
		t.Fatalf("config port = %d, want 9100 (越界那次不能落盘)", cfg.LastPort)
	}
}

// 状态页要显示局域网地址（手表/手机连的是这个）：/api/status 必须带 ip，
// 之前只给 hostname，桌面上「获取不到 IP」就是这么来的。
func TestStatusIncludesLANIP(t *testing.T) {
	srv := New(store.NewIn(t.TempDir()))
	// 桌面 UI 在本机：走 loopback 放行分支
	req := httptest.NewRequest(http.MethodGet, "/api/status", nil)
	req.RemoteAddr = "127.0.0.1:55555"
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/status = %d, want 200", rec.Code)
	}
	var body struct {
		System struct {
			Hostname string `json:"hostname"`
			Version  string `json:"version"`
			IP       string `json:"ip"`
		} `json:"system"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v (%s)", err, rec.Body.String())
	}
	if body.System.IP == "" {
		t.Fatalf("status.system.ip 为空，UI 上就显示不出地址：%s", rec.Body.String())
	}
	if !strings.Contains(body.System.IP, ":") && !strings.Contains(body.System.IP, ".") {
		t.Fatalf("status.system.ip = %q，不是合法地址", body.System.IP)
	}
}

// loopback 放行只对本机生效：LAN 上来的人（手表/手机）仍然必须带已信任的 device id。
func TestStatusStillGatesRemotePeers(t *testing.T) {
	srv := New(store.NewIn(t.TempDir()))

	call := func(remote, deviceID string) int {
		req := httptest.NewRequest(http.MethodGet, "/api/status", nil)
		req.RemoteAddr = remote
		if deviceID != "" {
			req.Header.Set("X-Device-ID", deviceID)
		}
		rec := httptest.NewRecorder()
		srv.Handler().ServeHTTP(rec, req)
		return rec.Code
	}

	if code := call("192.168.1.20:40000", ""); code != http.StatusUnauthorized {
		t.Fatalf("远端无 device id = %d, want 401", code)
	}
	if code := call("192.168.1.20:40000", "未信任设备"); code != http.StatusForbidden {
		t.Fatalf("远端未信任 device = %d, want 403", code)
	}
	if err := srv.store.AddTrustedDevice("手表", "手表 A"); err != nil {
		t.Fatal(err)
	}
	if code := call("192.168.1.20:40000", "手表"); code != http.StatusOK {
		t.Fatalf("远端已信任 device = %d, want 200", code)
	}
	// 本机不需要 device id
	if code := call("127.0.0.1:55555", ""); code != http.StatusOK {
		t.Fatalf("loopback 无 device id = %d, want 200", code)
	}
}

// 市场安装/卸载：UI 侧下载校验后把 .hpk 交给 Go 安装（解包逻辑只有 store 一份），
// 装完要立刻能在 /api/hydro/list 里看到，卸载后要消失。
func TestInstallAndUninstallPlugin(t *testing.T) {
	srv := New(store.NewIn(t.TempDir()))
	// 用 app 层的测试包（真 .hpk：与仓库 examples 同源）
	hpk := filepath.Join("..", "..", "examples", "com.demo.weather.hpk")
	if _, err := os.Stat(hpk); err != nil {
		t.Skipf("示例插件包不存在: %v", err)
	}
	abs, err := filepath.Abs(hpk)
	if err != nil {
		t.Fatal(err)
	}

	// 没注入热注册钩子时装完也能返回成功（列表由 reload 负责刷新）
	rec := doJSON(t, srv, http.MethodPost, "/api/plugins/install", map[string]string{"path": abs})
	if rec.Code != http.StatusOK {
		t.Fatalf("POST /api/plugins/install = %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	if !contains(rec.Body.String(), "com.demo.weather") {
		t.Fatalf("install body missing plugin id: %s", rec.Body.String())
	}

	// 路径不存在要如实报错
	rec = doJSON(t, srv, http.MethodPost, "/api/plugins/install", map[string]string{"path": "/nope.hpk"})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("安装不存在的包 = %d, want 400", rec.Code)
	}

	rec = doJSON(t, srv, http.MethodDelete, "/api/plugins/com.demo.weather", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("DELETE /api/plugins/com.demo.weather = %d, want 200", rec.Code)
	}
	list := srv.store.ListPlugins()
	for _, p := range list {
		if p.ID == "com.demo.weather" {
			t.Fatal("插件卸载后仍在列表里")
		}
	}
}
