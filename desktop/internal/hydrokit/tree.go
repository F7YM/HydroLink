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
type Node struct {
	Type     string            `json:"type"`
	Style    map[string]any    `json:"style,omitempty"`
	Children []*Node           `json:"children,omitempty"`
	Events   map[string]string `json:"events,omitempty"`
	Props    map[string]any    `json:"props,omitempty"`
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

func (n *Node) Child(c *Node) *Node {
	n.Children = append(n.Children, c)
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
	RefreshMs int    `json:"refreshMs,omitempty"`
	Root      *Node  `json:"root"`
}

func (v *View) JSON() ([]byte, error) { return json.Marshal(v) }
