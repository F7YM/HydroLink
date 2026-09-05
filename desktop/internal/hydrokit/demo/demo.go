// Package demo provides a sample HydroPlugin used to exercise HydroKit.
package demo

import "hydrolink-desktop/internal/hydrokit"

// Weather is a trivial plugin: one HydroApp ("main") and one HydroWidget
// ("now"), both returning a small static tree with a refresh button/event.
type Weather struct {
	pkg hydrokit.Package
}

// NewWeather returns a Weather plugin.
func NewWeather() *Weather {
	return &Weather{pkg: hydrokit.Package{
		ID: "com.demo.weather", Name: "示例天气", Version: "0.1.0",
	}}
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
	title := "示例天气"
	if req.Trigger == hydrokit.TriggerEvent && req.Event.Handler == "refresh" {
		title = "已刷新"
	}
	row := func(c ...*hydrokit.Node) *hydrokit.Node {
		return hydrokit.New(hydrokit.TypeRow).Add(c...)
	}
	return hydrokit.Column(
		hydrokit.Text(title).StyleSet("fontSize", 28).StyleSet("weight", 700),
		hydrokit.Text("北京 · 23°C 晴"),
		row(
			hydrokit.Icon("wb_sunny").StyleSet("size", 24),
			hydrokit.Text("23°C").StyleSet("fontSize", 40).StyleSet("weight", 700),
		),
		row(
			hydrokit.Icon("battery"),
			hydrokit.Text("电量 87%"),
		),
		row(
			hydrokit.Text("省电模式"),
			hydrokit.New(hydrokit.TypeSwitch).Prop("checked", true).On("change", "toggle"),
		),
		row(
			hydrokit.New(hydrokit.TypeBadge).Prop("label", "beta").StyleSet("backgroundColor", "#434659"),
			hydrokit.New(hydrokit.TypeSpinner),
			hydrokit.New(hydrokit.TypeInput).Prop("placeholder", "输入地名").Prop("value", "北京").On("change", "place"),
		),
		hydrokit.Button("刷新").On("tap", "refresh"),
	).StyleSet("padding", 16).StyleSet("gap", 8)
}

func (w *Weather) widgetRoot(req hydrokit.Request) *hydrokit.Node {
	return hydrokit.Column(
		hydrokit.Icon("wb_sunny").StyleSet("size", 18),
		hydrokit.Text("23°C"),
	).StyleSet("padding", 8).StyleSet("gap", 4)
}
