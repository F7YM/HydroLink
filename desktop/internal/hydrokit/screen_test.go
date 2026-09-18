package hydrokit

import (
	"runtime"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// capturePlugin records the last Request it was asked to render.
type capturePlugin struct {
	m    Manifest
	last Request
}

func (c *capturePlugin) Manifest() Manifest { return c.m }
func (c *capturePlugin) View(surface, item string, req Request) (*View, error) {
	c.last = req
	return &View{Root: Text("ok")}, nil
}

func TestScreenQueryParsing(t *testing.T) {
	cp := &capturePlugin{m: Manifest{
		Package: Package{ID: "com.x.screen"},
		Apps:    []Item{{ID: "main"}},
	}}
	rt := NewRuntime(nil)
	if err := rt.Register(cp); err != nil {
		t.Fatalf("register: %v", err)
	}
	h := NewHandler(rt)

	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet,
		"/api/hydro/com.x.screen/app/main/view?shape=pill&width=192&height=490&dpr=2&dt=band", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("view -> %d", rr.Code)
	}

	want := Screen{Shape: "pill", Width: 192, Height: 490, DPR: 2, DeviceType: "band"}
	if cp.last.Screen != want {
		t.Fatalf("screen = %+v, want %+v", cp.last.Screen, want)
	}
	if cp.last.Trigger != TriggerOpen {
		t.Fatalf("trigger = %q, want open", cp.last.Trigger)
	}
}

func TestScreenQueryAbsent(t *testing.T) {
	cp := &capturePlugin{m: Manifest{
		Package: Package{ID: "com.x.screen2"},
		Apps:    []Item{{ID: "main"}},
	}}
	rt := NewRuntime(nil)
	_ = rt.Register(cp)
	h := NewHandler(rt)

	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost,
		"/api/hydro/com.x.screen2/app/main/view", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("view -> %d", rr.Code)
	}
	want := Screen{}
	if cp.last.Screen != want {
		t.Fatalf("screen = %+v, want zero value", cp.last.Screen)
	}
}

func TestNodeMediaJSONRoundtrip(t *testing.T) {
	n := &Node{
		Type:  TypeText,
		Style: map[string]any{"fontSize": 28, "color": "#FFFFFF"},
		Props: map[string]any{"text": "hi"},
		Media: map[string]map[string]any{
			"circle": {"fontSize": 26},
			"pill":   {"fontSize": 22, "color": "#D6DCE8"},
		},
	}
	data, err := json.Marshal(n)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var back Node
	if err := json.Unmarshal(data, &back); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	data2, err := json.Marshal(&back)
	if err != nil {
		t.Fatalf("remarshal: %v", err)
	}
	if string(data) != string(data2) {
		t.Fatalf("roundtrip mismatch:\n%s\n%s", data, data2)
	}
	if _, ok := back.Media["circle"]["fontSize"].(float64); !ok {
		t.Fatalf("circle.fontSize type = %T, want float64", back.Media["circle"]["fontSize"])
	}
}

func TestMediaSetHelper(t *testing.T) {
	n := New(TypeText).MediaSet("circle", map[string]any{"fontSize": 26})
	n.MediaSet("circle", map[string]any{"color": "#FFF"}).MediaSet("pill", map[string]any{"fontSize": 22})
	if len(n.Media["circle"]) != 2 {
		t.Fatalf("circle media = %+v, want 2 keys", n.Media["circle"])
	}
	if n.Media["circle"]["fontSize"] != 26 || n.Media["circle"]["color"] != "#FFF" {
		t.Fatalf("circle media = %+v", n.Media["circle"])
	}
	if n.Media["pill"]["fontSize"] != 22 {
		t.Fatalf("pill media = %+v", n.Media["pill"])
	}
}

// 无头部的传输通道（安卓互联通道）：设备侧存储快照可以从 query 或 event body
// 传进来，服务端三条路都要认，否则插件 watch 作用域状态会丢。
func TestStoreSnapshotFromQueryAndBody(t *testing.T) {
	plugin := &capturePlugin{m: Manifest{
		Package: Package{ID: "test.pkg", Version: "1.0.0"},
		Apps:    []Item{{ID: "main"}},
	}}
	rt := NewRuntime(nil)
	if err := rt.Register(plugin); err != nil {
		t.Fatalf("register: %v", err)
	}
	h := NewHandler(rt)

	// query
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet,
		"/api/hydro/test.pkg/app/main/view?store=%7B%22city%22%3A%22%E5%8C%97%E4%BA%AC%22%7D", nil)
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("query store: code=%d body=%s", rec.Code, rec.Body.String())
	}
	if got := plugin.last.Store["city"]; got != "北京" {
		t.Fatalf("query store 未生效: %#v", plugin.last.Store)
	}

	// event body
	plugin.last = Request{}
	rec = httptest.NewRecorder()
	body := `{"event":{"handler":"refresh"},"store":{"city":"上海"}}`
	req = httptest.NewRequest(http.MethodPost, "/api/hydro/test.pkg/app/main/event", strings.NewReader(body))
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("body store: code=%d body=%s", rec.Code, rec.Body.String())
	}
	if got := plugin.last.Store["city"]; got != "上海" {
		t.Fatalf("body store 未生效: %#v", plugin.last.Store)
	}
	if plugin.last.Event.Handler != "refresh" {
		t.Fatalf("事件未解析: %#v", plugin.last.Event)
	}
}

// 插件按平台分支要能拿到宿主 OS（安卓端同构，os="android"）。
func TestRequestCarriesHostInfo(t *testing.T) {
	plugin := &capturePlugin{m: Manifest{
		Package: Package{ID: "test.pkg", Version: "1.0.0"},
		Apps:    []Item{{ID: "main"}},
	}}
	rt := NewRuntime(nil)
	if err := rt.Register(plugin); err != nil {
		t.Fatalf("register: %v", err)
	}
	h := NewHandler(rt)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/hydro/test.pkg/app/main/view", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("code=%d", rec.Code)
	}
	host := plugin.last.Host
	if host["os"] == "" || host["arch"] == "" || host["app"] == "" {
		t.Fatalf("req.host 不完整: %#v", host)
	}
	if host["os"] != runtime.GOOS {
		t.Fatalf("os=%q 应为 %q", host["os"], runtime.GOOS)
	}
}
