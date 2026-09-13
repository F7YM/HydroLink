package system

// Notify 发一条系统通知。
//
// 平台实现分两条：
//   - Windows：PowerShell 的 WinRT 投影（不碰进程内 COM）。原因：进程内 COM
//     的公寓模态（apartment）不是我们能控制的，beeep→go-toast 在真机上必报
//     `RPC_E_CHANGED_MODE`；它的回退还会起带控制台窗口的 PowerShell。
//   - Linux/macOS：beeep（dbus / osascript），一直很稳。
//
// 可用 SetNotifyEnabled(false) 关掉，用于二分定位"是不是通知把进程搞崩了"。
func Notify(title, message string) error {
	if !notifyEnabled() {
		logf("system: 通知已禁用（排查模式），跳过: %s", title)
		return nil
	}
	return platformNotify(title, message)
}
