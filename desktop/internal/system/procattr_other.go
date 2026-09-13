//go:build !windows

package system

import "syscall"

// hideWindowAttr：非 Windows 没有"子进程控制台窗口"这回事。
func hideWindowAttr() *syscall.SysProcAttr { return nil }
