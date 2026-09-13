//go:build windows

package system

import (
	"fmt"
	"os/exec"
	"strings"
)

// Windows 通知不用进程内 COM（beeep → go-toast）。
//
// 真机实测：go-toast 在 pushCOM 里调 ole.RoInitialize(MTA)，而调用线程已经被
// 别的代码设成 STA，于是返回 RPC_E_CHANGED_MODE —— 通知 100% 失败
// （日志里能看到 "系统通知发送失败: RoInitialize: 无法在设置线程模式后对其加以更改"）。
// 它的失败回退又会起一个**带控制台窗口**的 PowerShell（就是之前看到的闪黑窗）。
//
// 这里直接用 PowerShell 的 WinRT 投影：不碰进程内 COM（公寓模态与我们无关），
// 子进程窗口隐藏，失败只是返回 error 由调用方记日志，不会影响进程存活。

const toastAppID = "HydroLink"

// notify 弹一条 toast。
func notify(title, message string) error {
	script := fmt.Sprintf(`
$ErrorActionPreference = 'SilentlyContinue'
[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] > $null
[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType=WindowsRuntime] > $null
$xml = New-Object Windows.Data.Xml.Dom.XmlDocument
$xml.LoadXml("<toast><visual><binding template='ToastGeneric'><text>%s</text><text>%s</text></binding></visual></toast>")
$toast = New-Object Windows.UI.Notifications.ToastNotification $xml
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('%s').Show($toast)
`, xmlEscape(title), xmlEscape(message), toastAppID)

	cmd := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
	cmd.SysProcAttr = hideWindowAttr()
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("通知发送失败: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// xmlEscape 同时处理 XML 与 PowerShell 单引号字符串。
func xmlEscape(s string) string {
	r := strings.NewReplacer(
		"&", "&amp;",
		"<", "&lt;",
		">", "&gt;",
		"'", "''",
		"\"", "&quot;",
	)
	return r.Replace(s)
}
