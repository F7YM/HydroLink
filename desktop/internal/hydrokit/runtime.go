package hydrokit

import (
	"fmt"
	"sync"
)

// Runtime is the registry of loaded HydroPlugins, keyed by package id.
type Runtime struct {
	mu      sync.RWMutex
	plugins map[string]Plugin
	host    Host
}

// NewRuntime creates an empty registry backed by host.
func NewRuntime(host Host) *Runtime {
	if host == nil {
		host = NopHost()
	}
	return &Runtime{plugins: map[string]Plugin{}, host: host}
}

// Register adds a plugin after validating its manifest (unique package id,
// non-empty app/widget registrations, unique item ids).
func (rt *Runtime) Register(p Plugin) error {
	m := p.Manifest()
	if err := m.Validate(); err != nil {
		return err
	}
	id := m.Package.ID
	rt.mu.Lock()
	defer rt.mu.Unlock()
	if _, dup := rt.plugins[id]; dup {
		return fmt.Errorf("hydrokit: plugin %q already registered", id)
	}
	rt.plugins[id] = p
	rt.host.Logf("info", "hydrokit: registered plugin %s v%s (%d apps, %d widgets)",
		id, m.Package.Version, len(m.Apps), len(m.Widgets))
	return nil
}

// Lookup returns a registered plugin by package id.
func (rt *Runtime) Lookup(pkg string) Plugin {
	rt.mu.RLock()
	defer rt.mu.RUnlock()
	return rt.plugins[pkg]
}

// PluginIDs returns the package ids of all registered plugins.
func (rt *Runtime) PluginIDs() []string {
	rt.mu.RLock()
	defer rt.mu.RUnlock()
	out := make([]string, 0, len(rt.plugins))
	for id := range rt.plugins {
		out = append(out, id)
	}
	return out
}

// Dispatch routes a surface/item request to the plugin registered as pkg.
// It returns a notFound error when the package or the surface item is not
// registered by that plugin.
func (rt *Runtime) Dispatch(pkg, surface, item string, req Request) (*View, error) {
	p := rt.Lookup(pkg)
	if p == nil {
		return nil, fmt.Errorf("hydrokit: unknown plugin package %q", pkg)
	}
	m := p.Manifest()
	switch surface {
	case SurfaceApp:
		if !m.HasApp(item) {
			return nil, fmt.Errorf("hydrokit: plugin %q has no app %q", pkg, item)
		}
	case SurfaceWidget:
		if !m.HasWidget(item) {
			return nil, fmt.Errorf("hydrokit: plugin %q has no widget %q", pkg, item)
		}
	default:
		return nil, fmt.Errorf("hydrokit: unknown surface %q", surface)
	}
	return p.View(surface, item, req)
}
