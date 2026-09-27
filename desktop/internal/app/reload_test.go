package app

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// TestPluginReloadEndpoint 覆盖桌面 Compose 端装插件的完整路径：
// UI 侧把 .hpk 装进数据目录后调 POST /api/hydro/reload，运行时立刻重扫——
// 不用重启 Go 服务。卸载后重扫也要把插件从 /api/hydro/list 里摘掉。
func TestPluginReloadEndpoint(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	srv, st, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	h := srv.Handler()

	listed := func() []string {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/hydro/list", nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("GET /api/hydro/list = %d, want 200", rec.Code)
		}
		var body struct {
			Plugins []struct {
				Package struct {
					ID string `json:"id"`
				} `json:"package"`
			} `json:"plugins"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
			t.Fatalf("decode: %v (%s)", err, rec.Body.String())
		}
		out := make([]string, 0, len(body.Plugins))
		for _, p := range body.Plugins {
			out = append(out, p.Package.ID)
		}
		return out
	}
	reload := func() []string {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/hydro/reload", nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("POST /api/hydro/reload = %d, want 200 (%s)", rec.Code, rec.Body.String())
		}
		var body struct {
			Plugins []string `json:"plugins"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
			t.Fatalf("decode: %v", err)
		}
		return body.Plugins
	}

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range map[string]string{
		"manifest.json": `{"id":"com.test.reload","name":"重载测试","author":"测试作者","version":"0.1.0","entry":"server.js","apps":[{"id":"main","name":"主页"}]}`,
		"server.js":     `module.exports = { manifest(){ return {} }, view: function(s,i,r){ return null } }`,
	} {
		w, _ := zw.Create(name)
		_, _ = w.Write([]byte(content))
	}
	_ = zw.Close()
	hpk := filepath.Join(t.TempDir(), "reload.hpk")
	if err := os.WriteFile(hpk, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := st.InstallPlugin(hpk); err != nil {
		t.Fatalf("InstallPlugin: %v", err)
	}

	// 装完（未热注册）时列表里还没有，重扫之后才有
	if got := reload(); len(got) != 1 || got[0] != "com.test.reload" {
		t.Fatalf("reload returned %v, want [com.test.reload]", got)
	}
	ids := listed()
	found := false
	for _, id := range ids {
		if id == "com.test.reload" {
			found = true
		}
	}
	if !found {
		t.Fatalf("/api/hydro/list = %v, want it to contain com.test.reload", ids)
	}

	// 卸载（UI 侧动作）后重扫，列表里就该没了
	if err := st.UninstallPlugin("com.test.reload"); err != nil {
		t.Fatalf("UninstallPlugin: %v", err)
	}
	if got := reload(); len(got) != 0 {
		t.Fatalf("reload after uninstall returned %v, want empty", got)
	}
	for _, id := range listed() {
		if id == "com.test.reload" {
			t.Fatalf("/api/hydro/list still contains com.test.reload after reload")
		}
	}
}
