// Package hydrokit is the plugin platform inside HydroLink. A HydroPlugin
// registers HydroApp and HydroWidget items; each is a declarative component
// tree that HydroKit renders on the Vela container (and, on the desktop, a
// separate Web surface).
//
// Tree protocol (V2, human-readable):
//
//	{ "type": "column", "style": {"gap":8}, "children":[
//	    { "type":"text", "props":{"text":"电量 90%"} },
//	    { "type":"button","props":{"label":"刷新"},"events":{"tap":"refresh"} }
//	]}
//
// Multi-screen adaptation:
//
//   - The container sends the device screen as query params on every
//     /api/hydro request: ?shape=circle&width=480&height=480&dpr=2&dt=watch
//     (shape is normalized rect|circle|pill). Plugins receive it in
//     Request.Screen and may build different trees per screen.
//
//   - A node may also declare declarative style overrides via "media", keyed
//     by screen shape (circle/rect/pill). The container merges them over the
//     base style, later keys winning per property: base style < shape key.
//     Unknown keys are ignored.
//
//     { "type":"text", "style":{"fontSize":28},
//     "media":{"circle":{"fontSize":26},"pill":{"fontSize":22}} }
package hydrokit

import "encoding/json"

// Component type names.
const (
	TypeColumn  = "column"
	TypeRow     = "row"
	TypeScroll  = "scroll"
	TypeCard    = "card"
	TypeText    = "text"
	TypeIcon    = "icon"
	TypeImage   = "image"
	TypeSpinner = "spinner"
	TypeBadge   = "badge"
	TypeButton  = "button"
	TypeSwitch  = "switch"
	TypeInput   = "input"
)

// Surface names addressed over /api/hydro/{package}/{surface}/{item}/...
const (
	SurfaceApp     = "app"
	SurfaceWidget  = "widget"
	SurfaceDesktop = "desktop"
)

// Node is one element of the declarative component tree.
//
//	Type     the component type (text/button/row/...)
//	Style    full CSS-like properties, e.g. {"padding":16,"fontSize":28}
//	Children child nodes (layout containers)
//	Events   interactions, e.g. {"tap":"refresh","change":"onSwitch"}
//	Props    component-specific data, e.g. {"text":"hi"} / {"checked":true}
//	Media    per-screen style overrides (see package doc for semantics)
type Node struct {
	Type     string                    `json:"type"`
	Style    map[string]any            `json:"style,omitempty"`
	Children []*Node                   `json:"children,omitempty"`
	Events   map[string]string         `json:"events,omitempty"`
	Props    map[string]any            `json:"props,omitempty"`
	Media    map[string]map[string]any `json:"media,omitempty"`
}

// New returns a Node with helpers to build it fluently.
func New(typ string) *Node { return &Node{Type: typ} }

func (n *Node) StyleSet(k string, v any) *Node {
	if n.Style == nil {
		n.Style = map[string]any{}
	}
	n.Style[k] = v
	return n
}

func (n *Node) Prop(k string, v any) *Node {
	if n.Props == nil {
		n.Props = map[string]any{}
	}
	n.Props[k] = v
	return n
}

func (n *Node) On(event, handler string) *Node {
	if n.Events == nil {
		n.Events = map[string]string{}
	}
	n.Events[event] = handler
	return n
}

// MediaSet adds per-screen style overrides under a match tag (circle/rect/
// pill); it merges into any existing overrides for that tag.
func (n *Node) MediaSet(tag string, style map[string]any) *Node {
	if n.Media == nil {
		n.Media = map[string]map[string]any{}
	}
	if n.Media[tag] == nil {
		n.Media[tag] = map[string]any{}
	}
	for k, v := range style {
		n.Media[tag][k] = v
	}
	return n
}

func (n *Node) Child(c *Node) *Node {
	n.Children = append(n.Children, c)
	return n
}

// Add appends multiple child nodes.
func (n *Node) Add(cs ...*Node) *Node {
	n.Children = append(n.Children, cs...)
	return n
}

// ---- Convenience constructors ----

func Column(children ...*Node) *Node {
	return &Node{Type: TypeColumn, Children: children}
}

func Text(s string) *Node {
	return &Node{Type: TypeText, Props: map[string]any{"text": s}}
}

func Button(label string) *Node {
	return &Node{Type: TypeButton, Props: map[string]any{"label": label}}
}

func Icon(name string) *Node {
	return &Node{Type: TypeIcon, Props: map[string]any{"name": name}}
}

// View is the payload returned to a surface: a root tree + optional refresh
// interval (ms) after which the container re-requests the view.
type View struct {
	RefreshMs int   `json:"refreshMs,omitempty"`
	Root      *Node `json:"root"`
}

func (v *View) JSON() ([]byte, error) { return json.Marshal(v) }
