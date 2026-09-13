//go:build windows

package main

import (
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	modUser32Fatal  = windows.NewLazySystemDLL("user32.dll")
	procMessageBoxW = modUser32Fatal.NewProc("MessageBoxW")
)

const (
	mbOK            = 0x00000000
	mbIconError     = 0x00000010
	mbSetForeground = 0x00010000
	mbTopmost       = 0x00040000
)

// fatalDialog 在 Windows 上弹一个原生错误框。
//
// 桌面端是 GUI 子系统（-H windowsgui），没有控制台：一旦启动阶段 fatal，
// 用户看到的就是"闪一下就没了、什么都不说"。原生 MessageBox 不依赖 Wails
// 窗口，能在任何时刻显示。
func fatalDialog(title, msg string) {
	t, err1 := syscall.UTF16PtrFromString(title)
	m, err2 := syscall.UTF16PtrFromString(msg)
	if err1 != nil || err2 != nil {
		return
	}
	procMessageBoxW.Call(
		0,
		uintptr(unsafe.Pointer(m)),
		uintptr(unsafe.Pointer(t)),
		uintptr(mbOK|mbIconError|mbSetForeground|mbTopmost),
	)
}
