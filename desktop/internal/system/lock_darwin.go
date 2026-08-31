//go:build darwin

package system

import "os/exec"

// LockScreen locks the macOS session via the Ctrl+Cmd+Q keystroke through
// System Events; falls back to display sleep.
func LockScreen() bool {
	scripts := []string{
		`tell application "System Events" to key code 12 using {control down, command down}`,
		`tell application "System Events" to keystroke "q" using {control down, command down}`,
	}
	for _, s := range scripts {
		if _, err := exec.Command("osascript", "-e", s).Output(); err == nil {
			return true
		}
	}
	if _, err := exec.Command("pmset", "displaysleepnow").Output(); err == nil {
		return true
	}
	return false
}
