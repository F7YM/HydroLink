//go:build linux

// Package tray shows a HydroLink icon in the desktop tray area and provides a
// small context menu (显示主窗口 / 退出).
//
// On Linux the tray area is the freedesktop StatusNotifierItem protocol
// (org.kde.StatusNotifierItem + com.canonical.dbusmenu over the session bus),
// which is what KDE Plasma, GNOME (with the appindicator extension) and
// Wayland sessions all use — the deprecated XEmbed/GTK status icons are not
// visible there. The implementation deliberately needs no cgo tray library
// (libappindicator is often absent), only godbus.
package tray

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"sync"
	"time"

	"github.com/disintegration/imaging"
	"github.com/godbus/dbus/v5"
	"github.com/godbus/dbus/v5/prop"
)

const (
	watcherName  = "org.kde.StatusNotifierWatcher"
	watcherPath  = "/StatusNotifierWatcher"
	watcherIface = "org.kde.StatusNotifierWatcher"

	itemIface = "org.kde.StatusNotifierItem"
	itemPath  = "/StatusNotifierItem"

	menuIface = "com.canonical.dbusmenu"
	menuPath  = "/MenuBar"

	menuIDShow = 1
	menuIDQuit = 3
)

// Options configures the tray icon.
type Options struct {
	// IconPNG is the source image for the tray pixmaps (usually appicon.png).
	IconPNG []byte
	// Title is the status item name (ApplicationStatus category).
	Title string
	// Tooltip carries the hover tooltip (title + subtitle).
	Tooltip  string
	Subtitle string
	// Logf receives diagnostics; nil disables logging.
	Logf func(format string, args ...any)
	// OnActivate is called on left click, OnQuit from the menu's 退出 entry.
	OnActivate func()
	OnQuit     func()
}

// Tray is a registered StatusNotifierItem.
type Tray struct {
	opts Options
	name string
	conn *dbus.Conn

	mu      sync.Mutex
	stopped bool
}

func (t *Tray) logf(format string, args ...any) {
	if t.opts.Logf != nil {
		t.opts.Logf(format, args...)
	}
}

// pixmap is the SNI IconPixmap struct: "(iiay)" with ARGB32 data in network
// byte order.
type pixmap struct {
	Width  int32
	Height int32
	Data   []byte
}

type toolTip struct {
	IconName   string
	IconPixmap []pixmap
	Title      string
	Subtitle   string
}

// layoutNode is com.canonical.dbusmenu's "(ia{sv}av)" layout node.
type layoutNode struct {
	ID       int32
	Props    map[string]dbus.Variant
	Children []dbus.Variant
}

// Start registers the tray icon. It returns an error when the session has no
// StatusNotifier host (e.g. a bare WM): the caller can then report "托盘不可用"
// instead of silently doing nothing.
func Start(opts Options) (*Tray, error) {
	if len(opts.IconPNG) == 0 {
		return nil, fmt.Errorf("托盘图标为空")
	}
	if opts.Title == "" {
		opts.Title = "HydroLink"
	}

	conn, err := privateSessionBus()
	if err != nil {
		return nil, err
	}

	name := fmt.Sprintf("org.kde.StatusNotifierItem-%d-1", os.Getpid())
	reply, err := conn.RequestName(name, dbus.NameFlagDoNotQueue)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("申请托盘总线名失败: %v", err)
	}
	if reply != dbus.RequestNameReplyPrimaryOwner {
		conn.Close()
		return nil, fmt.Errorf("托盘总线名已被占用: %s", name)
	}

	t := &Tray{opts: opts, name: name, conn: conn}

	pix, err := iconPixmaps(opts.IconPNG)
	if err != nil {
		conn.ReleaseName(name)
		conn.Close()
		return nil, fmt.Errorf("解析托盘图标失败: %v", err)
	}

	// org.kde.StatusNotifierItem properties.
	sniSpec := map[string]map[string]*prop.Prop{
		itemIface: {
			"Category":            ro("ApplicationStatus"),
			"Id":                  ro("hydrolink"),
			"Title":               ro(opts.Title),
			"Status":              ro("Active"),
			"IconName":            ro(""),
			"IconPixmap":          ro(pix),
			"AttentionIconName":   ro(""),
			"AttentionIconPixmap": ro([]pixmap{}),
			"AttentionMovieName":  ro(""),
			"OverlayIconName":     ro(""),
			"OverlayIconPixmap":   ro([]pixmap{}),
			"ToolTip":             ro(toolTip{IconName: "", IconPixmap: pix, Title: opts.Title, Subtitle: opts.Subtitle}),
			"ItemIsMenu":          ro(false),
			"Menu":                ro(dbus.ObjectPath(menuPath)),
			"WindowId":            ro(int32(0)),
		},
	}
	if _, err := prop.Export(conn, itemPath, sniSpec); err != nil {
		t.Stop()
		return nil, fmt.Errorf("导出托盘属性失败: %v", err)
	}

	// com.canonical.dbusmenu properties.
	menuSpec := map[string]map[string]*prop.Prop{
		menuIface: {
			"Version":       ro(uint32(3)),
			"TextDirection": ro("ltr"),
			"Status":        ro("normal"),
			"IconThemePath": ro([]string{}),
		},
	}
	if _, err := prop.Export(conn, menuPath, menuSpec); err != nil {
		t.Stop()
		return nil, fmt.Errorf("导出菜单属性失败: %v", err)
	}

	if err := conn.Export(&sniItem{t: t}, itemPath, itemIface); err != nil {
		t.Stop()
		return nil, fmt.Errorf("导出托盘方法失败: %v", err)
	}
	if err := conn.Export(&dbusMenu{t: t}, menuPath, menuIface); err != nil {
		t.Stop()
		return nil, fmt.Errorf("导出菜单方法失败: %v", err)
	}

	// Register with the StatusNotifierWatcher (the Plasma/kded host).
	obj := conn.Object(watcherName, dbus.ObjectPath(watcherPath))
	if err := obj.Call(watcherIface+".RegisterStatusNotifierItem", 0, name).Err; err != nil {
		t.Stop()
		return nil, fmt.Errorf("注册托盘失败（未找到 StatusNotifier 宿主）: %v", err)
	}

	t.logf("tray: 已注册 %s", name)
	return t, nil
}

// Stop removes the icon and releases the bus name. Safe to call twice.
func (t *Tray) Stop() {
	t.mu.Lock()
	if t.stopped {
		t.mu.Unlock()
		return
	}
	t.stopped = true
	t.mu.Unlock()

	if t.conn == nil {
		return
	}
	// Unexport everything we added, then drop the name.
	_ = t.conn.Export(nil, itemPath, itemIface)
	_ = t.conn.Export(nil, itemPath, "org.freedesktop.DBus.Properties")
	_ = t.conn.Export(nil, menuPath, menuIface)
	_ = t.conn.Export(nil, menuPath, "org.freedesktop.DBus.Properties")
	_, _ = t.conn.ReleaseName(t.name)
	t.conn.Close()
	t.conn = nil
	t.logf("tray: 已移除 %s", t.name)
}

// Running reports whether the icon is currently registered.
func (t *Tray) Running() bool {
	if t == nil {
		return false
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	return !t.stopped
}

func ro(v any) *prop.Prop {
	return &prop.Prop{Value: v, Writable: false, Emit: prop.EmitFalse}
}

// privateSessionBus opens a private connection so Stop() can close it without
// disturbing other users of the shared session bus (beeep notifications).
func privateSessionBus() (*dbus.Conn, error) {
	conn, err := dbus.SessionBusPrivate()
	if err != nil {
		return nil, fmt.Errorf("连接会话总线失败: %v", err)
	}
	if err := conn.Auth(nil); err != nil {
		conn.Close()
		return nil, fmt.Errorf("会话总线认证失败: %v", err)
	}
	if err := conn.Hello(); err != nil {
		conn.Close()
		return nil, fmt.Errorf("会话总线握手失败: %v", err)
	}
	return conn, nil
}

// iconPixmaps decodes the PNG once and produces the sizes panels ask for.
func iconPixmaps(data []byte) ([]pixmap, error) {
	src, err := png.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	out := make([]pixmap, 0, 3)
	for _, size := range []int{22, 32, 48} {
		dst := imaging.Resize(src, size, size, imaging.Lanczos)
		out = append(out, pixmap{
			Width:  int32(size),
			Height: int32(size),
			Data:   argb32(dst),
		})
	}
	return out, nil
}

// argb32 converts an image to the ARGB32 big-endian byte order the SNI spec
// requires (alpha first, straight alpha, premultiplied expected by some hosts —
// panels accept straight alpha, KDE composites it correctly).
func argb32(img image.Image) []byte {
	b := img.Bounds()
	data := make([]byte, 0, b.Dx()*b.Dy()*4)
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			r, g, bl, a := color.NRGBAModel.Convert(img.At(x, y)).(color.NRGBA).RGBA()
			data = append(data, byte(a>>8), byte(r>>8), byte(g>>8), byte(bl>>8))
		}
	}
	return data
}

// sniItem implements the org.kde.StatusNotifierItem methods.
type sniItem struct{ t *Tray }

func (s *sniItem) Activate(x, y int32) *dbus.Error {
	s.t.logf("tray: 左键点击")
	if s.t.opts.OnActivate != nil {
		go s.t.opts.OnActivate()
	}
	return nil
}

func (s *sniItem) SecondaryActivate(x, y int32) *dbus.Error {
	s.t.logf("tray: 中键点击")
	if s.t.opts.OnActivate != nil {
		go s.t.opts.OnActivate()
	}
	return nil
}

// ContextMenu is only meaningful for items without a Menu property; KDE shows
// the exported DBusMenu itself, so this stays a no-op.
func (s *sniItem) ContextMenu(x, y int32) *dbus.Error { return nil }

func (s *sniItem) Scroll(delta int32, orientation string) *dbus.Error { return nil }

// dbusMenu implements the subset of com.canonical.dbusmenu hosts actually call.
type dbusMenu struct{ t *Tray }

func props(pairs map[string]any) map[string]dbus.Variant {
	out := make(map[string]dbus.Variant, len(pairs))
	for k, v := range pairs {
		out[k] = dbus.MakeVariant(v)
	}
	return out
}

func (m *dbusMenu) layout() layoutNode {
	show := layoutNode{
		ID: menuIDShow,
		Props: props(map[string]any{
			"label":   "显示主窗口",
			"enabled": true,
			"visible": true,
		}),
	}
	sep := layoutNode{
		ID: 2,
		Props: props(map[string]any{
			"type":    "separator",
			"visible": true,
		}),
	}
	quit := layoutNode{
		ID: menuIDQuit,
		Props: props(map[string]any{
			"label":   "退出",
			"enabled": true,
			"visible": true,
		}),
	}
	return layoutNode{
		ID:    0,
		Props: props(map[string]any{"children-display": "submenu"}),
		Children: []dbus.Variant{
			dbus.MakeVariant(show),
			dbus.MakeVariant(sep),
			dbus.MakeVariant(quit),
		},
	}
}

func (m *dbusMenu) GetLayout(parentID int32, recursionDepth int32, propertyNames []string) (uint32, layoutNode, *dbus.Error) {
	return uint32(time.Now().Unix()), m.layout(), nil
}

func (m *dbusMenu) GetGroupProperties(ids []int32, propertyNames []string) ([]struct {
	ID    int32
	Props map[string]dbus.Variant
}, *dbus.Error) {
	return nil, nil
}

func (m *dbusMenu) GetProperty(id int32, name string) (dbus.Variant, *dbus.Error) {
	node := m.layout()
	check := func(n layoutNode) (dbus.Variant, bool) {
		if v, ok := n.Props[name]; ok {
			return v, true
		}
		return dbus.MakeVariant(""), false
	}
	if id == node.ID {
		if v, ok := check(node); ok {
			return v, nil
		}
	}
	for _, c := range node.Children {
		if n, ok := c.Value().(layoutNode); ok && n.ID == id {
			if v, ok := check(n); ok {
				return v, nil
			}
		}
	}
	return dbus.MakeVariant(""), nil
}

func (m *dbusMenu) Event(id int32, eventID string, data dbus.Variant, timestamp uint32) *dbus.Error {
	if eventID != "clicked" {
		return nil
	}
	switch id {
	case menuIDShow:
		if m.t.opts.OnActivate != nil {
			go m.t.opts.OnActivate()
		}
	case menuIDQuit:
		if m.t.opts.OnQuit != nil {
			go m.t.opts.OnQuit()
		}
	}
	return nil
}

func (m *dbusMenu) AboutToShow(id int32) (bool, *dbus.Error) { return false, nil }

func (m *dbusMenu) AboutToShowGroup(ids []int32) ([]int32, []int32, *dbus.Error) {
	return nil, nil, nil
}
