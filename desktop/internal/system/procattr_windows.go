//go:build windows

package system

import (
	"syscall"

	"golang.org/x/sys/windows"
)

// hideWindowAttr 让子进程不弹控制台窗口。
//
// GUI 子系统（-H windowsgui）的应用里，每次 CreateProcess 一个控制台程序
// （powershell / cmd）都会闪一个黑窗口 —— 查电池、跑用户命令都会触发。
// CREATE_NO_WINDOW 让子进程完全不创建控制台。
func hideWindowAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: windows.CREATE_NO_WINDOW,
	}
}
