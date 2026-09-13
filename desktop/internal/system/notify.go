package system

import "github.com/gen2brain/beeep"

// Notify 发一条系统通知（beeep：Linux 走 dbus、macOS 走 osascript、
// Windows 走 go-toast 的 WinRT COM）。
//
// 可用 SetNotifyEnabled(false) 关掉，用于二分定位"是不是通知把进程搞崩了"。
func Notify(title, message string) error {
	if !notifyEnabled() {
		logf("system: 通知已禁用（排查模式），跳过: %s", title)
		return nil
	}
	return beeep.Notify(title, message, "")
}
