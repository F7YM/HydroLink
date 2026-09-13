//go:build !windows

package system

import "github.com/gen2brain/beeep"

func init() {
	// beeep 会把这个名字当作通知来源（Linux 的 dbus / notify-send -a）。
	beeep.AppName = "HydroLink"
}

// platformNotify：Linux 走 dbus、macOS 走 osascript。
func platformNotify(title, message string) error {
	return beeep.Notify(title, message, "")
}
