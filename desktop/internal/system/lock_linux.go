//go:build linux

package system

import "os/exec"

// LockScreen locks the session by trying a list of lock commands. Returns
// true if any succeeded (exit 0).
func LockScreen() bool {
	candidates := [][]string{
		{"xdg-screensaver", "lock"},
		{"loginctl", "lock-session"},
		{"dm-tool", "lock"},
	}
	for _, args := range candidates {
		if _, err := exec.Command(args[0], args[1:]...).Output(); err == nil {
			return true
		}
	}
	return false
}
