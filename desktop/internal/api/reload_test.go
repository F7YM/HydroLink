package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// 插件目录重扫端点：UI 侧装完 .hpk 调它让运行时热加载，不必重启服务。
func TestReloadRouteCallsReloader(t *testing.T) {
	srv := New(nil)
	called := 0
	srv.SetPluginReloader(func() ([]string, error) {
		called++
		return []string{"com.a", "com.b"}, nil
	})

	req := httptest.NewRequest(http.MethodPost, "/api/hydro/reload", nil)
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("POST /api/hydro/reload = %d, want 200", rec.Code)
	}
	if called != 1 {
		t.Fatalf("reloader called %d times, want 1", called)
	}
	var body struct {
		Status  string   `json:"status"`
		Plugins []string `json:"plugins"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v (%s)", err, rec.Body.String())
	}
	if body.Status != "ok" || len(body.Plugins) != 2 {
		t.Fatalf("body = %+v, want status=ok with 2 plugins", body)
	}
}

// GET 不行：重扫会改运行时状态，只允许 POST（与其它管理端点一致）。
func TestReloadRouteRejectsGet(t *testing.T) {
	srv := New(nil)
	srv.SetPluginReloader(func() ([]string, error) { return nil, nil })

	req := httptest.NewRequest(http.MethodGet, "/api/hydro/reload", nil)
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET /api/hydro/reload = %d, want 405", rec.Code)
	}
}

// 没有注入 reloader 时（无头/嵌入模式）要如实报错，而不是假装成功。
func TestReloadRouteWithoutReloader(t *testing.T) {
	srv := New(nil)

	req := httptest.NewRequest(http.MethodPost, "/api/hydro/reload", nil)
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("POST /api/hydro/reload = %d, want 503", rec.Code)
	}
}
