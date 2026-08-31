//go:build windows

package system

import "os/exec"

// LockScreen locks the Windows workstation.
func LockScreen() bool {
	if _, err := exec.Command("rundll32.exe", "user32.dll,LockWorkStation").Output(); err == nil {
		return true
	}
	return false
}
