// Package demo provides a sample HydroPlugin used to exercise HydroKit.
package demo

import (
	"fmt"
	"sync"

	"hydrolink-desktop/internal/hydrokit"
)

// Weather is a trivial plugin: one HydroApp ("main") and one HydroWidget
// ("now"), both returning a small static tree with a refresh button/event.
// It keeps a little state so events actually change the rendered tree.
type Weather struct {
	pkg       hydrokit.Package
	mu        sync.Mutex
	powerSave bool
	place     string
}

// NewWeather returns a Weather plugin.
func NewWeather() *Weather {
	return &Weather{
		pkg:       hydrokit.Package{ID: "com.demo.weather", Name: "示例天气", Version: "0.1.0"},
		powerSave: true,
		place:     "北京",
	}
}

func (w *Weather) Manifest() hydrokit.Manifest {
	return hydrokit.Manifest{
		Package: w.pkg,
		Apps:    []hydrokit.Item{{ID: "main", Name: "天气"}},
		Widgets: []hydrokit.Item{{ID: "now", Name: "当前天气", RefreshMs: 60000}},
	}
}

func (w *Weather) View(surface, item string, req hydrokit.Request) (*hydrokit.View, error) {
	switch surface {
	case hydrokit.SurfaceApp:
		if item == "main" {
			return &hydrokit.View{Root: w.appRoot(req)}, nil
		}
	case hydrokit.SurfaceWidget:
		if item == "now" {
			return &hydrokit.View{Root: w.widgetRoot(req)}, nil
		}
	}
	return hydrokit.NotImplemented(surface, item)
}

func (w *Weather) appRoot(req hydrokit.Request) *hydrokit.Node {
	w.mu.Lock()
	defer w.mu.Unlock()

	title := "示例天气"
	if req.Trigger == hydrokit.TriggerEvent {
		switch req.Event.Handler {
		case "refresh":
			title = "已刷新"
		case "toggle":
			w.powerSave = !w.powerSave
		case "place":
			if v := req.Params["value"]; v != "" {
				w.place = v
			}
		}
	}
	subtitle := fmt.Sprintf("%s · 23°C 晴", w.place)

	row := func(c ...*hydrokit.Node) *hydrokit.Node {
		return hydrokit.New(hydrokit.TypeRow).Add(c...)
	}
	return hydrokit.Column(
		hydrokit.Text(title).
			StyleSet("fontSize", 28).StyleSet("fontWeight", 700).StyleSet("color", "#FFFFFF").
			StyleSet("marginBottom", 4),
		hydrokit.Text(subtitle).
			StyleSet("fontSize", 22).StyleSet("color", "#D6DCE8").
			StyleSet("marginBottom", 16),
		row(
			hydrokit.Text("23°C").
				StyleSet("fontSize", 44).StyleSet("fontWeight", 700).StyleSet("color", "#FFFFFF"),
		).StyleSet("marginBottom", 6),
		row(
			hydrokit.Icon("battery").StyleSet("width", 26).StyleSet("height", 26),
			hydrokit.Text("电量 87%").
				StyleSet("fontSize", 22).StyleSet("color", "#D6DCE8").
				StyleSet("marginLeft", 10),
		).StyleSet("marginBottom", 14),
		row(
			hydrokit.Text("省电模式").StyleSet("fontSize", 22).StyleSet("color", "#D6DCE8"),
			hydrokit.New(hydrokit.TypeSwitch).Prop("checked", w.powerSave).On("change", "toggle"),
		).StyleSet("marginBottom", 16).StyleSet("justifyContent", "space-between"),
		row(
			hydrokit.New(hydrokit.TypeBadge).Prop("label", "beta").
				StyleSet("backgroundColor", "#434659"),
			hydrokit.New(hydrokit.TypeSpinner).StyleSet("marginLeft", 14),
			hydrokit.New(hydrokit.TypeInput).
				Prop("placeholder", "输入地名").Prop("value", w.place).On("change", "place").
				StyleSet("marginLeft", 14),
		).StyleSet("marginBottom", 18),
		hydrokit.Button("刷新").On("tap", "refresh").
			StyleSet("backgroundColor", "#BAC3FF").StyleSet("marginBottom", 4),
	).StyleSet("padding", 16)
}

func (w *Weather) widgetRoot(req hydrokit.Request) *hydrokit.Node {
	return hydrokit.Column(
		hydrokit.Icon("battery").StyleSet("width", 18).StyleSet("height", 18),
		hydrokit.Text("23°C").
			StyleSet("fontSize", 24).StyleSet("fontWeight", 700).StyleSet("color", "#FFFFFF").
			StyleSet("marginTop", 2),
	).StyleSet("padding", 8)
}
