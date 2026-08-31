// Package system provides the platform-adapted system capabilities used by
// the HydroStation API: hostname/version, performance, battery, music, lock
// and shell command execution.
package system

import (
	"os"
	"runtime"
)

// Hostname returns the OS hostname (used for status.system.hostname).
func Hostname() string {
	h, err := os.Hostname()
	if err != nil || h == "" {
		return runtime.GOOS
	}
	return h
}

// Version is implemented per platform (info_linux.go / info_darwin.go /
// info_windows.go) and returns a human-readable OS version string.
