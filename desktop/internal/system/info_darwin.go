//go:build darwin

package system

import (
	"os/exec"
	"strings"
)

// Version returns the macOS version via `sw_vers`.
func Version() string {
	out, err := exec.Command("sw_vers", "-productVersion").Output()
	if err != nil {
		return "macOS"
	}
	return "macOS " + strings.TrimSpace(string(out))
}
