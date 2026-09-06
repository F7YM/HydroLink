// Package app wires the HydroLink API server together with the HydroKit
// plugin platform, shared by the GUI and headless entry points.
package app

import (
	"fmt"
	"log"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/hydrokit/demo"
	"hydrolink-desktop/internal/store"
)

// NewServer builds a store + API server with the HydroKit dispatcher mounted:
// bundled Go plugins plus every JS plugin installed under plugins/ (worker
// channel included, so headless mode also serves the worker endpoints — the
// worker session itself exists only in the GUI webview).
func NewServer() (*api.Server, *store.Store, error) {
	st, err := store.New()
	if err != nil {
		return nil, nil, fmt.Errorf("store: %w", err)
	}
	srv := api.New(st)

	// HydroKit: register bundled plugins, then JS plugins from disk.
	bridge := hydrokit.NewWorkerBridge()
	bridge.Logf = func(format string, args ...any) { log.Printf(format, args...) }
	rt := hydrokit.NewRuntime(nil)
	for _, p := range []hydrokit.Plugin{demo.NewWeather()} {
		if err := rt.Register(p); err != nil {
			return nil, nil, fmt.Errorf("hydrokit: %w", err)
		}
	}
	for _, info := range st.ListPlugins() {
		if info.Error != "" {
			log.Printf("hydrokit: skip plugin %s: %s", info.ID, info.Error)
			continue
		}
		m, err := st.GetPluginManifest(info.ID)
		if err != nil {
			log.Printf("hydrokit: skip plugin %s: %v", info.ID, err)
			continue
		}
		hm := toManifest(m)
		if err := rt.Register(hydrokit.NewJSPlugin(&hm, bridge)); err != nil {
			log.Printf("hydrokit: skip plugin %s: %v", info.ID, err)
			continue
		}
	}
	srv.SetHydroHandler(hydrokit.NewHandler(rt))
	srv.SetWorkerHandler(api.NewWorkerHandler(bridge, st))
	srv.SetWorkerBridge(bridge)

	return srv, st, nil
}

// toManifest converts a store plugin manifest into a hydrokit manifest.
func toManifest(m *store.PluginManifest) hydrokit.Manifest {
	out := hydrokit.Manifest{
		Package: hydrokit.Package{ID: m.ID, Name: m.Name, Version: m.Version},
		Apps:    make([]hydrokit.Item, 0, len(m.Apps)),
		Widgets: make([]hydrokit.Item, 0, len(m.Widgets)),
	}
	for _, a := range m.Apps {
		out.Apps = append(out.Apps, hydrokit.Item{ID: a.ID, Name: a.Name})
	}
	for _, w := range m.Widgets {
		out.Widgets = append(out.Widgets, hydrokit.Item{ID: w.ID, Name: w.Name, RefreshMs: w.RefreshMs})
	}
	return out
}
