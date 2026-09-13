//go:build windows

package system

import "os/exec"

// LockScreen locks the Windows workstation.
func LockScreen() bool {
	cmd := exec.Command("rundll32.exe", "user32.dll,LockWorkStation")
	cmd.SysProcAttr = hideWindowAttr()
	if _, err := cmd.Output(); err == nil {
		return true
	}
	return false
}
