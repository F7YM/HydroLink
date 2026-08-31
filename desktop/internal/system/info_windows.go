//go:build windows

package system

import "golang.org/x/sys/windows"

// Version returns the Windows version via RtlGetVersion (works on all
// Windows versions without manifest issues).
func Version() string {
	info := windows.RtlGetVersion()
	if info == nil {
		return "Windows"
	}
	return "Windows " + itoa(int(info.MajorVersion)) + "." + itoa(int(info.MinorVersion)) +
		" (build " + itoa(int(info.BuildNumber)) + ")"
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [12]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
