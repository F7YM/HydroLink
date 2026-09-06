package hydrokit

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
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
			"pill":   {"fontSize": 22},
			"narrow": {"fontSize": 20, "color": "#D6DCE8"},
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
	if _, ok := back.Media["narrow"]["fontSize"].(float64); !ok {
		t.Fatalf("narrow.fontSize type = %T, want float64", back.Media["narrow"]["fontSize"])
	}
}

func TestMediaSetHelper(t *testing.T) {
	n := New(TypeText).MediaSet("pill", map[string]any{"fontSize": 22})
	n.MediaSet("pill", map[string]any{"color": "#FFF"}).MediaSet("narrow", map[string]any{"fontSize": 20})
	if len(n.Media["pill"]) != 2 {
		t.Fatalf("pill media = %+v, want 2 keys", n.Media["pill"])
	}
	if n.Media["pill"]["fontSize"] != 22 || n.Media["pill"]["color"] != "#FFF" {
		t.Fatalf("pill media = %+v", n.Media["pill"])
	}
	if n.Media["narrow"]["fontSize"] != 20 {
		t.Fatalf("narrow media = %+v", n.Media["narrow"])
	}
}
