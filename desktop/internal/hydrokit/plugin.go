package hydrokit

import "fmt"

// Trigger reasons for requesting a view.
const (
	TriggerOpen    = "open"    // surface first opened
	TriggerRefresh = "refresh" // manual / on-return refresh
	TriggerTimer   = "timer"   // refreshMs elapsed
	TriggerEvent   = "event"   // user interaction happened
)

// Event describes a user interaction reported by a component.
type Event struct {
	Handler string `json:"handler"`          // events value, e.g. "refresh"
	Source  string `json:"source,omitempty"` // "tap" | "change" | ...
	ID      string `json:"id,omitempty"`
	Value   any    `json:"value,omitempty"`
}

// Screen describes the display of the device requesting a view. The hydro
// container reports it (device.getInfo on Vela) as query params, so plugins
// can adapt trees per screen shape/size:
//
//	?shape=circle&width=480&height=480&dpr=2&dt=watch
//
// Shape values are normalized: rect | circle | pill.
type Screen struct {
	Shape      string  `json:"shape,omitempty"`
	Width      int     `json:"width,omitempty"`
	Height     int     `json:"height,omitempty"`
	DPR        float64 `json:"dpr,omitempty"`
	DeviceType string  `json:"deviceType,omitempty"`
}

// Request carries why a view is being requested.
type Request struct {
	Trigger string            `json:"trigger"`
	Event   Event             `json:"event,omitempty"`
	Params  map[string]string `json:"params,omitempty"`
	Screen  Screen            `json:"screen,omitempty"`
	// Store is the device-side (watch) storage snapshot the container uploads
	// with every request (header X-Hydro-Store). Plugins read it for
	// scope:'watch' kit.storage reads; writes go back in View.Storage.
	Store map[string]any `json:"store,omitempty"`
	// Host tells the plugin which platform it is running on (os = runtime.GOOS:
	// linux/windows/darwin, plus arch and app version) so one .hpk can branch
	// per platform instead of sniffing the environment. Mirrored on Android
	// with os="android"; also exposed top-level in the WASM ABI context.
	Host map[string]string `json:"host,omitempty"`
}

// Host is the set of capabilities HydroKit exposes to a plugin. Milestone 1
// only provides logging; http/kv/system/permission-gated functions are added
// in later milestones.
type Host interface {
	Logf(level, format string, args ...any)
}

// Plugin is a HydroPlugin. In-process Go plugins implement it directly; a
// WASM-backed shim will implement the same interface by calling into the
// module's exports over the HydroKit ABI.
type Plugin interface {
	// Manifest declares package, registered apps/widgets and permissions.
	Manifest() Manifest
	// View returns the current tree for a surface item. Returning (nil, nil)
	// means "no change".
	View(surface, item string, req Request) (*View, error)
}

// NotImplemented is a helper for plugins that don't handle a surface.
func NotImplemented(surface, item string) (*View, error) {
	return nil, fmt.Errorf("hydrokit: %s %q not provided by this plugin", surface, item)
}

// ---- host helpers ----

type nopHost struct{}

func (nopHost) Logf(level, format string, args ...any) {}

// NopHost is a Host that discards logs (tests/headless).
func NopHost() Host { return nopHost{} }
