// Package app wires the HydroLink API server together with the HydroKit
// plugin platform, shared by the GUI and headless entry points.
package app

import (
	"fmt"
	"log"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/store"
	"hydrolink-desktop/internal/system"
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
	// 宿主版本报给插件（req.host.app）
	hydrokit.HostVersion = api.Version

	// HydroKit: register JS plugins from disk (the weather demo ships as an
	// installable plugin package: desktop/examples/com.demo.weather.hpk).
	bridge := hydrokit.NewWorkerBridge()
	bridge.Logf = func(format string, args ...any) { log.Printf(format, args...) }
	rt := hydrokit.NewRuntime(nil)
	for _, info := range st.ListPlugins() {
		if err := registerDiskPlugin(rt, bridge, st, info.ID); err != nil {
			log.Printf("hydrokit: skip plugin %s: %v", info.ID, err)
		}
	}
	// 平台层（音乐/电池等）的面包屑与错误写进应用日志：进程若被硬崩掉，
	// 日志里最后一条就是崩溃点。
	system.SetLogger(func(format string, args ...any) {
		srv.AddLog(fmt.Sprintf(format, args...), "info")
	})

	srv.SetHydroHandler(hydrokit.NewHandler(rt))
	srv.SetWorkerHandler(api.NewWorkerHandler(bridge, st))
	srv.SetWorkerBridge(bridge)

	// Hot-load/unload after GUI install/uninstall — no server restart needed.
	srv.SetPluginHooks(
		func(id string) error { return registerDiskPlugin(rt, bridge, st, id) },
		func(id string) error {
			rt.Unregister(id)
			return nil
		},
	)

	return srv, st, nil
}

// registerDiskPlugin registers one installed plugin package in the runtime.
func registerDiskPlugin(rt *hydrokit.Runtime, bridge *hydrokit.WorkerBridge, st *store.Store, id string) error {
	m, err := st.GetPluginManifest(id)
	if err != nil {
		return err
	}
	hm := toManifest(m)
	// 更新/重装：先注销旧注册再注册新清单（实现热替换）
	rt.Unregister(id)
	return rt.Register(hydrokit.NewJSPlugin(&hm, bridge))
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
