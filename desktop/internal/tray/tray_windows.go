//go:build windows

package tray

import (
	"bytes"
	"fmt"
	"image/png"
	"runtime"
	"sync"
	"syscall"
	"unsafe"

	"github.com/disintegration/imaging"
	"golang.org/x/sys/windows"
)

// Windows 托盘：Shell_NotifyIcon + 一个隐藏窗口的消息泵。
//
// 注意不能用 HWND_MESSAGE（消息专用窗口收不到 TaskbarCreated 广播），
// 否则 explorer 重启后图标不会回来。所有 Win32 调用都在同一个
// runtime.LockOSThread 的 goroutine 里，窗口过程通过 syscall.NewCallback 注册。

var (
	modUser32  = windows.NewLazySystemDLL("user32.dll")
	modGdi32   = windows.NewLazySystemDLL("gdi32.dll")
	modShell32 = windows.NewLazySystemDLL("shell32.dll")
	modKernel  = windows.NewLazySystemDLL("kernel32.dll")

	procRegisterClassExW       = modUser32.NewProc("RegisterClassExW")
	procCreateWindowExW        = modUser32.NewProc("CreateWindowExW")
	procDefWindowProcW         = modUser32.NewProc("DefWindowProcW")
	procDestroyWindow          = modUser32.NewProc("DestroyWindow")
	procGetMessageW            = modUser32.NewProc("GetMessageW")
	procTranslateMessage       = modUser32.NewProc("TranslateMessage")
	procDispatchMessageW       = modUser32.NewProc("DispatchMessageW")
	procPostQuitMessage        = modUser32.NewProc("PostQuitMessage")
	procPostMessageW           = modUser32.NewProc("PostMessageW")
	procRegisterWindowMessageW = modUser32.NewProc("RegisterWindowMessageW")
	procCreatePopupMenu        = modUser32.NewProc("CreatePopupMenu")
	procAppendMenuW            = modUser32.NewProc("AppendMenuW")
	procTrackPopupMenu         = modUser32.NewProc("TrackPopupMenu")
	procDestroyMenu            = modUser32.NewProc("DestroyMenu")
	procGetCursorPos           = modUser32.NewProc("GetCursorPos")
	procSetForegroundWindow    = modUser32.NewProc("SetForegroundWindow")
	procCreateIconFromResource = modUser32.NewProc("CreateIconFromResourceEx")
	procDestroyIcon            = modUser32.NewProc("DestroyIcon")

	procShellNotifyIconW = modShell32.NewProc("Shell_NotifyIconW")
	procGetModuleHandleW = modKernel.NewProc("GetModuleHandleW")
)

const (
	wmApp         = 0x8000 + 1 // WM_APP+1：托盘回调消息
	wmDestroy     = 0x0002
	wmClose       = 0x0010
	wmNull        = 0x0000
	wmLButtonUp   = 0x0202
	wmRButtonUp   = 0x0205
	wmContextMenu = 0x007B

	nimAdd        = 0
	nimDelete     = 2
	nimSetVersion = 4

	nifMessage = 0x00000001
	nifIcon    = 0x00000002
	nifTip     = 0x00000004

	notifyIconVersion4 = 4

	mfString    = 0x00000000
	mfSeparator = 0x00000800

	tpmRightButton = 0x0002
	tpmReturnCmd   = 0x0100
	tpmNonotify    = 0x0080

	idiApplication = 32512
)

type wndClassEx struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     windows.Handle
	HIcon         windows.Handle
	HCursor       windows.Handle
	HbrBackground windows.Handle
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       windows.Handle
}

type winPoint struct{ X, Y int32 }

type winMsg struct {
	Hwnd    windows.HWND
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      winPoint
}

// notifyIconData 对应 NOTIFYICONDATAW（Vista+ 完整布局）。
type notifyIconData struct {
	CbSize           uint32
	HWnd             windows.HWND
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            windows.Handle
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UVersion         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         windows.GUID
	HBalloonIcon     windows.Handle
}

// Options 配置托盘图标（与 Linux 版字段保持一致；图标字节各平台取各需）。
type Options struct {
	// IconPNG 是 Linux/GNOME 用的 PNG 源图。
	IconPNG []byte
	// IconICO 是 Windows 用的 ICO 源图（HICON 需要 ICO/位图数据）。
	IconICO []byte
	Title   string
	// Tooltip 是悬停提示；为空时用 DefaultTooltip。
	Tooltip  string
	Subtitle string
	Logf     func(format string, args ...any)
	// OnActivate 左键点击，OnQuit 菜单"退出"。
	OnActivate func()
	OnQuit     func()
}

// Tray 是已注册的 Windows 托盘图标。
type Tray struct {
	opts Options

	mu       sync.Mutex
	stopped  bool
	hwnd     windows.HWND
	hicon    windows.Handle
	ready    chan error
	done     chan struct{}
	threadID uint32
}

func (t *Tray) logf(format string, args ...any) {
	if t.opts.Logf != nil {
		t.opts.Logf(format, args...)
	}
}

func wndProc(hwnd, msg, wparam, lparam uintptr) uintptr {
	// 窗口句柄对应的 Tray 通过 GWLP_USERDATA 关联。
	ptr, _, _ := procGetWindowLongPtrW.Call(hwnd, gwlpUserdata)
	if ptr != 0 {
		t := (*Tray)(unsafe.Pointer(ptr))
		if handled, result := t.handleMessage(hwnd, msg, wparam, lparam); handled {
			return result
		}
	}
	ret, _, _ := procDefWindowProcW.Call(hwnd, msg, wparam, lparam)
	return ret
}

var (
	procGetWindowLongPtrW = modUser32.NewProc("GetWindowLongPtrW")
	procSetWindowLongPtrW = modUser32.NewProc("SetWindowLongPtrW")
)

const gwlpUserdata = ^uintptr(0) - 20 // GWLP_USERDATA = -21

// Start 注册托盘图标；会话没有 explorer/托盘区时返回错误。
func Start(opts Options) (*Tray, error) {
	// 图标来源：优先 ICO（资源格式最标准），没有就退回 PNG 自绘 HICON。
	// 这样托盘不依赖 icon.ico 是否被打进二进制（缺 ICO 也不再整个托盘不可用）。
	if opts.Title == "" {
		opts.Title = DefaultTitle
	}
	if opts.Tooltip == "" {
		opts.Tooltip = DefaultTooltip
	}

	t := &Tray{
		opts:  opts,
		ready: make(chan error, 1),
		done:  make(chan struct{}),
	}
	icon, err := t.buildIcon()
	if err != nil {
		return nil, err
	}
	t.hicon = icon

	go t.run()
	if err := <-t.ready; err != nil {
		<-t.done
		return nil, err
	}
	t.logf("tray: 已注册（Windows，hwnd=%v）", t.hwnd)
	return t, nil
}

// buildIcon 依可用数据构造 HICON：ICO 优先，退化为 PNG。
func (t *Tray) buildIcon() (windows.Handle, error) {
	if len(t.opts.IconICO) > 0 {
		img, w, h, err := IconFromICO(t.opts.IconICO)
		if err == nil {
			if hicon, err := iconFromICOImage(img, w, h); err == nil {
				return hicon, nil
			} else {
				t.logf("tray: ICO 转 HICON 失败(%v)，改用 PNG", err)
			}
		} else {
			t.logf("tray: ICO 解析失败(%v)，改用 PNG", err)
		}
	}
	if len(t.opts.IconPNG) > 0 {
		hicon, err := iconFromPNG(t.opts.IconPNG)
		if err == nil {
			return hicon, nil
		}
		return 0, fmt.Errorf("PNG 转 HICON 失败: %v", err)
	}
	if len(t.opts.IconICO) == 0 {
		return 0, fmt.Errorf("缺少图标数据（ICO 与 PNG 都为空）")
	}
	return 0, fmt.Errorf("图标数据不可用")
}

// iconFromICOImage 用 CreateIconFromResourceEx 把 ICO 图像数据变成 HICON。
func iconFromICOImage(img []byte, w, h int) (windows.Handle, error) {
	if len(img) == 0 {
		return 0, fmt.Errorf("ICO 图像数据为空")
	}
	// dwVer = 0x00030000 表示 Vista+ 的图标资源格式。
	hicon, _, errno := procCreateIconFromResource.Call(
		uintptr(unsafe.Pointer(&img[0])),
		uintptr(len(img)),
		1, // fIcon = TRUE
		0x00030000,
		uintptr(int32(w)),
		uintptr(int32(h)),
		0,
	)
	if hicon == 0 {
		return 0, fmt.Errorf("CreateIconFromResourceEx 失败: %v", errno)
	}
	return windows.Handle(hicon), nil
}

// run 在锁定线程上创建隐藏窗口、添加图标并泵消息。
func (t *Tray) run() {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	defer close(t.done)

	hInstance, _, _ := procGetModuleHandleW.Call(0)
	className, _ := syscall.UTF16PtrFromString("HydroLinkTrayWindow")
	title, _ := syscall.UTF16PtrFromString(t.opts.Title)

	if err := ensureWindowClass(className, hInstance); err != nil {
		t.ready <- err
		return
	}

	hwnd, _, errno := procCreateWindowExW.Call(
		0,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(title)),
		0, 0, 0, 0, 0, // 不可见、无尺寸
		0, 0,
		hInstance,
		0,
	)
	if hwnd == 0 {
		t.ready <- fmt.Errorf("CreateWindowExW 失败: %v", errno)
		return
	}
	t.hwnd = windows.HWND(hwnd)
	procSetWindowLongPtrW.Call(hwnd, gwlpUserdata, uintptr(unsafe.Pointer(t)))

	taskbarCreated, _, _ = procRegisterWindowMessageW.Call(uintptr(unsafe.Pointer(mustUTF16("TaskbarCreated"))))

	if err := t.addIcon(); err != nil {
		t.ready <- err
		procDestroyWindow.Call(hwnd)
		return
	}
	t.threadID = windows.GetCurrentThreadId()
	t.ready <- nil

	var msg winMsg
	for {
		ret, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if int32(ret) <= 0 { // 0 = WM_QUIT，-1 = 错误
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&msg)))
	}
}

// 窗口类是全进程共享的：注册一次即可。重复注册会返回
// ERROR_CLASS_ALREADY_EXISTS，而"设置里关掉托盘再打开"正好会走到这里
// （曾经因此第二次启用必定失败）。
var (
	classOnce sync.Once
	classErr  error
)

func ensureWindowClass(className *uint16, hInstance uintptr) error {
	classOnce.Do(func() {
		wc := wndClassEx{
			CbSize:        uint32(unsafe.Sizeof(wndClassEx{})),
			LpfnWndProc:   syscall.NewCallback(wndProc),
			HInstance:     windows.Handle(hInstance),
			LpszClassName: className,
		}
		atom, _, errno := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))
		if atom == 0 && errno != windows.ERROR_CLASS_ALREADY_EXISTS {
			classErr = fmt.Errorf("RegisterClassExW 失败: %v", errno)
		}
	})
	return classErr
}

var taskbarCreated uintptr

func mustUTF16(s string) *uint16 {
	p, err := syscall.UTF16PtrFromString(s)
	if err != nil {
		return nil
	}
	return p
}

func (t *Tray) notifyData() notifyIconData {
	nid := notifyIconData{
		HWnd:             t.hwnd,
		UID:              1,
		UFlags:           nifMessage | nifIcon | nifTip,
		UCallbackMessage: wmApp,
		HIcon:            t.hicon,
	}
	copy(nid.SzTip[:], syscall.StringToUTF16(t.opts.Tooltip))
	nid.CbSize = uint32(unsafe.Sizeof(nid))
	return nid
}

func (t *Tray) addIcon() error {
	nid := t.notifyData()
	ok, _, errno := procShellNotifyIconW.Call(nimAdd, uintptr(unsafe.Pointer(&nid)))
	if ok == 0 {
		return fmt.Errorf("Shell_NotifyIcon(NIM_ADD) 失败: %v（托盘区不可用？）", errno)
	}
	// 版本 4：右键菜单消息从 lParam 的 HIWORD 传坐标，行为更接近系统图标。
	ver := t.notifyData()
	ver.UVersion = notifyIconVersion4
	procShellNotifyIconW.Call(nimSetVersion, uintptr(unsafe.Pointer(&ver)))
	return nil
}

func (t *Tray) deleteIcon() {
	nid := t.notifyData()
	procShellNotifyIconW.Call(nimDelete, uintptr(unsafe.Pointer(&nid)))
}

// handleMessage 处理窗口消息；返回 (是否已处理, 结果值)。
func (t *Tray) handleMessage(hwnd, msg, wparam, lparam uintptr) (bool, uintptr) {
	// explorer 重启后会广播 TaskbarCreated，需要重新添加图标。
	if taskbarCreated != 0 && msg == taskbarCreated {
		t.logf("tray: explorer 重启，重新添加图标")
		_ = t.addIcon()
		return true, 0
	}
	switch msg {
	case wmApp:
		// 版本 4 起事件在 lParam 低 16 位；旧版整个 lParam 就是消息号，
		// 两者取 LOWORD 都成立。
		event := lparam & 0xFFFF
		switch event {
		case wmLButtonUp:
			t.logf("tray: 左键点击")
			if t.opts.OnActivate != nil {
				go t.opts.OnActivate()
			}
		case wmRButtonUp, wmContextMenu:
			t.showMenu()
		}
		return true, 0
	case wmClose:
		t.deleteIcon()
		if t.hicon != 0 {
			procDestroyIcon.Call(uintptr(t.hicon))
			t.hicon = 0
		}
		procDestroyWindow.Call(hwnd)
		procPostQuitMessage.Call(0)
		return true, 0
	}
	return false, 0
}

// CheckMenu 只构建菜单文件句柄后立即销毁，用于诊断（TrackPopupMenu 会阻塞，
// 不适合放进自动化探针）。
func CheckMenu() error {
	hmenu, _, errno := procCreatePopupMenu.Call()
	if hmenu == 0 {
		return fmt.Errorf("CreatePopupMenu 失败: %v", errno)
	}
	defer procDestroyMenu.Call(hmenu)
	for _, e := range MenuEntries() {
		if e.Separator {
			if ok, _, e2 := procAppendMenuW.Call(hmenu, mfSeparator, 0, 0); ok == 0 {
				return fmt.Errorf("AppendMenu(separator) 失败: %v", e2)
			}
			continue
		}
		label, err := syscall.UTF16PtrFromString(e.Label)
		if err != nil {
			return fmt.Errorf("标签转 UTF-16 失败: %v", err)
		}
		if ok, _, e2 := procAppendMenuW.Call(hmenu, mfString, uintptr(e.ID), uintptr(unsafe.Pointer(label))); ok == 0 {
			return fmt.Errorf("AppendMenu(%s) 失败: %v", e.Label, e2)
		}
	}
	return nil
}

// showMenu 弹出与 Linux 完全一致的菜单（同一份规格 menu.go）。
func (t *Tray) showMenu() {
	hmenu, _, _ := procCreatePopupMenu.Call()
	if hmenu == 0 {
		return
	}
	defer procDestroyMenu.Call(hmenu)

	for _, e := range MenuEntries() {
		if e.Separator {
			procAppendMenuW.Call(hmenu, mfSeparator, 0, 0)
			continue
		}
		label, _ := syscall.UTF16PtrFromString(e.Label)
		procAppendMenuW.Call(hmenu, mfString, uintptr(e.ID), uintptr(unsafe.Pointer(label)))
	}

	var pt winPoint
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	// TrackPopupMenu 的经典坑：不先 SetForegroundWindow 菜单点外面不会消失。
	procSetForegroundWindow.Call(uintptr(t.hwnd))
	cmd, _, _ := procTrackPopupMenu.Call(
		hmenu,
		tpmRightButton|tpmReturnCmd|tpmNonotify,
		uintptr(pt.X), uintptr(pt.Y), 0, uintptr(t.hwnd), 0,
	)
	procPostMessageW.Call(uintptr(t.hwnd), wmNull, 0, 0)

	switch int32(cmd) {
	case MenuIDShow:
		t.logf("tray: 菜单 显示主窗口")
		if t.opts.OnActivate != nil {
			go t.opts.OnActivate()
		}
	case MenuIDQuit:
		t.logf("tray: 菜单 退出")
		if t.opts.OnQuit != nil {
			go t.opts.OnQuit()
		}
	}
}

// Stop 移除图标并结束消息泵。幂等。
func (t *Tray) Stop() {
	t.mu.Lock()
	if t.stopped {
		t.mu.Unlock()
		return
	}
	t.stopped = true
	hwnd := t.hwnd
	t.mu.Unlock()

	if hwnd != 0 {
		procPostMessageW.Call(uintptr(hwnd), wmClose, 0, 0)
		<-t.done
	}
	t.logf("tray: 已移除（Windows）")
}

// Running 报告图标是否仍然注册着。
func (t *Tray) Running() bool {
	if t == nil {
		return false
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	return !t.stopped
}

// ---- PNG → HICON ----

var (
	procCreateDIBSection   = modGdi32.NewProc("CreateDIBSection")
	procCreateBitmap       = modGdi32.NewProc("CreateBitmap")
	procDeleteObject       = modGdi32.NewProc("DeleteObject")
	procCreateIconIndirect = modUser32.NewProc("CreateIconIndirect")
	procGetDC              = modUser32.NewProc("GetDC")
	procReleaseDC          = modUser32.NewProc("ReleaseDC")
)

type bitmapInfoHeader struct {
	Size          uint32
	Width         int32
	Height        int32
	Planes        uint16
	BitCount      uint16
	Compression   uint32
	SizeImage     uint32
	XPelsPerMeter int32
	YPelsPerMeter int32
	ClrUsed       uint32
	ClrImportant  uint32
}

type iconInfo struct {
	FIcon    int32
	XHotspot uint32
	YHotspot uint32
	HbmMask  windows.Handle
	HbmColor windows.Handle
}

// iconFromPNG 把 PNG 画成 32bpp DIB 再包装成 HICON。
// （CreateIconFromResourceEx 只吃 ICO/CUR 资源，PNG 必须自己搭 DIB。）
func iconFromPNG(data []byte) (windows.Handle, error) {
	src, err := png.Decode(bytes.NewReader(data))
	if err != nil {
		return 0, fmt.Errorf("解码 PNG 失败: %v", err)
	}
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= 0 || h <= 0 {
		return 0, fmt.Errorf("PNG 尺寸非法: %dx%d", w, h)
	}
	if w > 256 || h > 256 {
		// 托盘图标不需要太大，超过就缩到 32（避免 CreateDIBSection 浪费内存）
		src = imaging.Resize(src, 32, 32, imaging.Lanczos)
		b = src.Bounds()
		w, h = b.Dx(), b.Dy()
	}

	bi := bitmapInfoHeader{
		Size:        uint32(unsafe.Sizeof(bitmapInfoHeader{})),
		Width:       int32(w),
		Height:      int32(-h), // 负数 = 自上而下，方便按行填充
		Planes:      1,
		BitCount:    32,
		Compression: 0, // BI_RGB
	}
	hdc, _, _ := procGetDC.Call(0)
	if hdc == 0 {
		return 0, fmt.Errorf("GetDC 失败")
	}
	defer procReleaseDC.Call(0, hdc)

	var bits unsafe.Pointer
	hbmColor, _, errno := procCreateDIBSection.Call(
		hdc,
		uintptr(unsafe.Pointer(&bi)),
		0, // DIB_RGB_COLORS
		uintptr(unsafe.Pointer(&bits)),
		0,
		0,
	)
	if hbmColor == 0 || bits == nil {
		return 0, fmt.Errorf("CreateDIBSection 失败: %v", errno)
	}
	defer procDeleteObject.Call(hbmColor)

	// BGRA，DIB 的 alpha 会直接用于图标合成。
	px := unsafe.Slice((*byte)(bits), w*h*4)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			r, g, bl, a := src.At(b.Min.X+x, b.Min.Y+y).RGBA()
			o := (y*w + x) * 4
			px[o+0] = byte(bl >> 8)
			px[o+1] = byte(g >> 8)
			px[o+2] = byte(r >> 8)
			px[o+3] = byte(a >> 8)
		}
	}

	// 单色遮罩：32bpp 带 alpha 时可留空。
	hbmMask, _, errno := procCreateBitmap.Call(uintptr(w), uintptr(h), 1, 1, 0)
	if hbmMask == 0 {
		return 0, fmt.Errorf("CreateBitmap 失败: %v", errno)
	}
	defer procDeleteObject.Call(hbmMask)

	ii := iconInfo{FIcon: 1, HbmMask: windows.Handle(hbmMask), HbmColor: windows.Handle(hbmColor)}
	hicon, _, errno := procCreateIconIndirect.Call(uintptr(unsafe.Pointer(&ii)))
	if hicon == 0 {
		return 0, fmt.Errorf("CreateIconIndirect 失败: %v", errno)
	}
	return windows.Handle(hicon), nil
}
