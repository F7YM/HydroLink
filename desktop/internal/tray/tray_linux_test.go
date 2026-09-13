//go:build linux

package tray

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/godbus/dbus/v5"
	"github.com/godbus/dbus/v5/prop"
)

// 托盘测试必须跑在**私有会话总线**上：否则会往用户真实的 Plasma 托盘里塞图标。
// TestMain 自己起一个 dbus-daemon，并覆盖 DBUS_SESSION_BUS_ADDRESS。
func TestMain(m *testing.M) {
	if _, err := exec.LookPath("dbus-daemon"); err != nil {
		fmt.Fprintln(os.Stderr, "skip: dbus-daemon 不可用")
		os.Exit(0)
	}
	cmd := exec.Command("dbus-daemon", "--session", "--fork",
		"--print-address=1", "--print-pid=1")
	out, err := cmd.Output()
	if err != nil {
		fmt.Fprintf(os.Stderr, "启动私有 dbus-daemon 失败: %v\n", err)
		os.Exit(1)
	}
	lines := strings.Fields(strings.TrimSpace(string(out)))
	if len(lines) < 2 {
		fmt.Fprintf(os.Stderr, "无法解析 dbus-daemon 地址: %q\n", out)
		os.Exit(1)
	}
	os.Setenv("DBUS_SESSION_BUS_ADDRESS", lines[0])
	code := m.Run()
	_ = exec.Command("kill", lines[1]).Run()
	os.Exit(code)
}

func testPNG(t *testing.T, size int) []byte {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, size, size))
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			img.Set(x, y, color.NRGBA{R: 0xba, G: 0xc3, B: 0xff, A: 0xff})
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatalf("encode png: %v", err)
	}
	return buf.Bytes()
}

// fakeWatcher 冒充 org.kde.StatusNotifierWatcher，记录插件注册时上送的 bus name。
type fakeWatcher struct {
	got chan string
}

func (f *fakeWatcher) RegisterStatusNotifierItem(service string) *dbus.Error {
	select {
	case f.got <- service:
	default:
	}
	return nil
}

func startFakeWatcher(t *testing.T) (*fakeWatcher, *dbus.Conn) {
	t.Helper()
	conn, err := privateSessionBus()
	if err != nil {
		t.Fatalf("私有总线连接失败: %v", err)
	}
	reply, err := conn.RequestName(watcherName, dbus.NameFlagDoNotQueue)
	if err != nil || reply != dbus.RequestNameReplyPrimaryOwner {
		t.Fatalf("占用 watcher 名失败: %v (reply %v)", err, reply)
	}
	fw := &fakeWatcher{got: make(chan string, 4)}
	if err := conn.Export(fw, dbus.ObjectPath(watcherPath), watcherIface); err != nil {
		t.Fatalf("导出 watcher 方法失败: %v", err)
	}
	if _, err := prop.Export(conn, dbus.ObjectPath(watcherPath), map[string]map[string]*prop.Prop{
		watcherIface: {
			"IsStatusNotifierHostRegistered": ro(true),
			"ProtocolVersion":                ro(int32(0)),
			"RegisteredStatusNotifierItems":  ro([]string{}),
		},
	}); err != nil {
		t.Fatalf("导出 watcher 属性失败: %v", err)
	}
	return fw, conn
}

// 没有宿主时必须报错（而不是悄悄成功，让设置里的开关骗人）。
func TestStartWithoutWatcherFails(t *testing.T) {
	_, err := Start(Options{IconPNG: testPNG(t, 64), Title: "HydroLink"})
	if err == nil {
		t.Fatal("没有 StatusNotifier 宿主时 Start 应当失败")
	}
	if !strings.Contains(err.Error(), "注册托盘失败") {
		t.Fatalf("错误信息应当说明注册失败: %v", err)
	}
}

func TestRegisterMenuAndCallbacks(t *testing.T) {
	fw, host := startFakeWatcher(t)
	defer host.Close()

	activated := make(chan struct{}, 1)
	quit := make(chan struct{}, 1)
	tr, err := Start(Options{
		IconPNG:    testPNG(t, 64),
		Title:      "HydroLink",
		Subtitle:   "桌面服务端",
		OnActivate: func() { activated <- struct{}{} },
		OnQuit:     func() { quit <- struct{}{} },
	})
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer tr.Stop()

	// 1) watcher 收到了注册，且名字是进程唯一的 SNI 名
	var registered string
	select {
	case registered = <-fw.got:
	case <-time.After(2 * time.Second):
		t.Fatal("watcher 未收到 RegisterStatusNotifierItem")
	}
	if !strings.HasPrefix(registered, "org.kde.StatusNotifierItem-") {
		t.Fatalf("注册的 bus name 不符合 SNI 约定: %q", registered)
	}

	// 2) 通过总线读回属性（宿主就是这么读的）
	client, err := privateSessionBus()
	if err != nil {
		t.Fatalf("client 连接失败: %v", err)
	}
	defer client.Close()
	item := client.Object(registered, dbus.ObjectPath(itemPath))

	for key, want := range map[string]any{
		"Category": "ApplicationStatus",
		"Id":       "hydrolink",
		"Title":    "HydroLink",
		"Status":   "Active",
	} {
		v, err := item.GetProperty(itemIface + "." + key)
		if err != nil {
			t.Fatalf("读取属性 %s: %v", key, err)
		}
		if got := v.Value(); got != want {
			t.Errorf("属性 %s = %v, 期望 %v", key, got, want)
		}
	}

	menuProp, err := item.GetProperty(itemIface + ".Menu")
	if err != nil {
		t.Fatalf("读取 Menu 属性: %v", err)
	}
	if menuProp.Value() != dbus.ObjectPath(menuPath) {
		t.Errorf("Menu = %v, 期望 %v", menuProp.Value(), menuPath)
	}

	pixProp, err := item.GetProperty(itemIface + ".IconPixmap")
	if err != nil {
		t.Fatalf("读取 IconPixmap: %v", err)
	}
	// godbus 把结构体解码成 []interface{}，用 dbus.Store 转回具名类型
	var pix []pixmap
	if err := dbus.Store([]any{pixProp.Value()}, &pix); err != nil {
		t.Fatalf("IconPixmap 解码失败: %v", err)
	}
	if len(pix) == 0 {
		t.Fatalf("IconPixmap 为空（类型 %T）", pixProp.Value())
	}
	for _, p := range pix {
		if len(p.Data) != int(p.Width*p.Height*4) {
			t.Errorf("图标 %dx%d 的 ARGB32 数据长度 %d 不匹配", p.Width, p.Height, len(p.Data))
		}
		if p.Width == 22 && (p.Data[0] != 0xff || p.Data[1] != 0xba) {
			t.Errorf("ARGB32 字节序应为 A,R,G,B；实际 % x", p.Data[:4])
		}
	}

	// 3) 菜单布局：显示主窗口 / 分隔符 / 退出
	menu := client.Object(registered, dbus.ObjectPath(menuPath))
	var revision uint32
	var layout layoutNode
	if err := menu.Call(menuIface+".GetLayout", 0, int32(0), int32(-1), []string{}).
		Store(&revision, &layout); err != nil {
		t.Fatalf("GetLayout: %v", err)
	}
	if len(layout.Children) != 3 {
		t.Fatalf("菜单应有 3 项，实际 %d", len(layout.Children))
	}
	// 菜单内容不在这里重复硬编码：规格由 menu.go 定义、由 menu_test.go 钉住，
	// 这里只验证 DBusMenu 渲染结果与规格一致。
	spec := MenuEntries()
	if len(layout.Children) != len(spec) {
		t.Fatalf("菜单项数 = %d, 期望 %d", len(layout.Children), len(spec))
	}
	for i, e := range spec {
		var node layoutNode
		if err := dbus.Store([]any{layout.Children[i].Value()}, &node); err != nil {
			t.Fatalf("第 %d 项解码失败: %v", i, err)
		}
		if node.ID != e.ID {
			t.Errorf("第 %d 项 id = %d, 期望 %d", i, node.ID, e.ID)
		}
		if e.Separator {
			if got := fmt.Sprint(node.Props["type"].Value()); got != "separator" {
				t.Errorf("第 %d 项 type = %q, 期望 separator", i, got)
			}
			continue
		}
		if got := fmt.Sprint(node.Props["label"].Value()); got != e.Label {
			t.Errorf("第 %d 项 label = %q, 期望 %q", i, got, e.Label)
		}
	}

	// 4) 点击回调：菜单"显示主窗口"、菜单"退出"、以及左键 Activate
	if err := menu.Call(menuIface+".Event", 0, MenuIDShow, "clicked", dbus.MakeVariant(""), uint32(0)).Err; err != nil {
		t.Fatalf("Event(show): %v", err)
	}
	select {
	case <-activated:
	case <-time.After(2 * time.Second):
		t.Fatal("点击 显示主窗口 未触发 OnActivate")
	}
	if err := menu.Call(menuIface+".Event", 0, MenuIDQuit, "clicked", dbus.MakeVariant(""), uint32(0)).Err; err != nil {
		t.Fatalf("Event(quit): %v", err)
	}
	select {
	case <-quit:
	case <-time.After(2 * time.Second):
		t.Fatal("点击 退出 未触发 OnQuit")
	}
	if err := item.Call(itemIface+".Activate", 0, int32(0), int32(0)).Err; err != nil {
		t.Fatalf("Activate: %v", err)
	}
	select {
	case <-activated:
	case <-time.After(2 * time.Second):
		t.Fatal("左键点击未触发 OnActivate")
	}

	// 5) Stop 之后总线名应被释放（可以重新申请），且 Running() 为 false
	tr.Stop()
	if tr.Running() {
		t.Error("Stop 之后 Running() 仍为 true")
	}
	reply, err := client.RequestName(registered, dbus.NameFlagDoNotQueue)
	if err != nil {
		t.Fatalf("重新申请总线名: %v", err)
	}
	if reply != dbus.RequestNameReplyPrimaryOwner {
		t.Errorf("Stop 后总线名未释放（reply=%v）", reply)
	}
	tr.Stop() // 幂等
}

func TestIconPixmapsSizes(t *testing.T) {
	pix, err := iconPixmaps(testPNG(t, 128))
	if err != nil {
		t.Fatalf("iconPixmaps: %v", err)
	}
	if len(pix) != 3 {
		t.Fatalf("期望 3 种尺寸，实际 %d", len(pix))
	}
	for i, want := range []int32{22, 32, 48} {
		if pix[i].Width != want || pix[i].Height != want {
			t.Errorf("第 %d 个图标 = %dx%d, 期望 %dx%d", i, pix[i].Width, pix[i].Height, want, want)
		}
	}
	if _, err := iconPixmaps([]byte("not a png")); err == nil {
		t.Error("非法 PNG 应当报错")
	}
}
