package hydrokit

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type stubPlugin struct {
	m Manifest
}

func (s stubPlugin) Manifest() Manifest { return s.m }
func (s stubPlugin) View(surface, item string, req Request) (*View, error) {
	return &View{Root: Text("ok:" + surface + "/" + item)}, nil
}

func TestDispatchView(t *testing.T) {
	rt := NewRuntime(nil)
	err := rt.Register(stubPlugin{m: Manifest{
		Package: Package{ID: "com.x.a", Version: "1.0.0"},
		Apps:    []Item{{ID: "main"}},
		Widgets: []Item{{ID: "now"}},
	}})
	if err != nil {
		t.Fatalf("register: %v", err)
	}

	h := NewHandler(rt)
	for _, path := range []string{
		"/api/hydro/com.x.a/app/main/view",
		"/api/hydro/com.x.a/widget/now/view",
	} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, path, nil))
		if rr.Code != http.StatusOK {
			t.Fatalf("%s -> %d", path, rr.Code)
		}
		var v View
		if err := json.Unmarshal(rr.Body.Bytes(), &v); err != nil {
			t.Fatalf("view json: %v", err)
		}
		if v.Root == nil {
			t.Fatalf("expected root for %s", path)
		}
	}
}

func TestDispatchEvent(t *testing.T) {
	rt := NewRuntime(nil)
	_ = rt.Register(stubPlugin{m: Manifest{
		Package: Package{ID: "com.x.b"},
		Apps:    []Item{{ID: "main"}},
	}})
	h := NewHandler(rt)
	body := `{"event":{"handler":"refresh","value":1}}`
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost,
		"/api/hydro/com.x.b/app/main/event", bytes.NewBufferString(body)))
	if rr.Code != http.StatusOK {
		t.Fatalf("event -> %d: %s", rr.Code, rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), "app/main") {
		t.Fatalf("unexpected body %s", rr.Body.String())
	}
}

func TestUnknownPluginOrItem(t *testing.T) {
	rt := NewRuntime(nil)
	h := NewHandler(rt)
	for _, path := range []string{
		"/api/hydro/no.such/app/main/view",
		"/api/hydro/no.such/widget/x/view",
	} {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, path, nil))
		if rr.Code != http.StatusNotFound {
			t.Fatalf("%s -> %d (want 404)", path, rr.Code)
		}
	}
}

func TestRegisterDuplicate(t *testing.T) {
	rt := NewRuntime(nil)
	m := Manifest{Package: Package{ID: "com.x"}, Apps: []Item{{ID: "a"}}}
	if err := rt.Register(stubPlugin{m: m}); err != nil {
		t.Fatal(err)
	}
	if err := rt.Register(stubPlugin{m: m}); err == nil {
		t.Fatal("expected duplicate register error")
	}
}

// 启动器要能直接取插件条目的图标：包内相对路径必须被解析成绝对 URL
// （绝对 URL / data: 原样保留）。安卓端同构，见 HydroKitRoutesTest。
func TestListResolvesItemIcons(t *testing.T) {
	rt := NewRuntime(nil)
	err := rt.Register(stubPlugin{m: Manifest{
		Package: Package{ID: "com.x.icon", Version: "1.0.0"},
		Apps: []Item{
			{ID: "main", Name: "带图标", Icon: "assets/icon.png"},
			{ID: "abs", Name: "绝对地址", Icon: "https://example.com/i.png"},
			{ID: "none", Name: "无图标"},
		},
	}})
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	old := BaseURLFor
	BaseURLFor = func(*http.Request) string { return "http://192.168.1.5:9000" }
	defer func() { BaseURLFor = old }()

	h := NewHandler(rt)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/hydro/list", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("code=%d", rr.Code)
	}
	var body struct {
		Plugins []struct {
			Apps []Item `json:"apps"`
		} `json:"plugins"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	apps := body.Plugins[0].Apps
	want := "http://192.168.1.5:9000/api/hydro/worker/asset?package=com.x.icon&path=assets%2Ficon.png"
	if apps[0].Icon != want {
		t.Fatalf("相对图标未解析:\n got %s\nwant %s", apps[0].Icon, want)
	}
	if apps[1].Icon != "https://example.com/i.png" {
		t.Fatalf("绝对 URL 不该被改写: %s", apps[1].Icon)
	}
	if apps[2].Icon != "" {
		t.Fatalf("没有图标就该是空: %q", apps[2].Icon)
	}
}
