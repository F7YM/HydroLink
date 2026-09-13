package system

// Notify 发一条系统通知。
//
// 平台实现分两条：
//   - Windows：PowerShell 的 WinRT 投影（不碰进程内 COM）。原因：进程内 COM
//     的公寓模态（apartment）不是我们能控制的，beeep→go-toast 在真机上必报
//     `RPC_E_CHANGED_MODE`；它的回退还会起带控制台窗口的 PowerShell。
//   - Linux/macOS：beeep（dbus / osascript），一直很稳。
//
// 失败只返回 error 由调用方记日志，不影响进程存活。
func Notify(title, message string) error {
	return platformNotify(title, message)
}
