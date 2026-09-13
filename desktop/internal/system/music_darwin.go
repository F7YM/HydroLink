//go:build darwin

package system

import (
	"os/exec"
	"strings"
)

func osascript(script string) (string, bool) {
	out, err := exec.Command("osascript", "-e", script).Output()
	if err != nil {
		return "", false
	}
	return strings.TrimSpace(string(out)), true
}

// GetMusic reads the current track from the macOS Music app via osascript.
func GetMusic() MusicInfo {
	out, ok := osascript(`tell application "System Events" to get {name, artist} of current track of application "Music"`)
	if !ok {
		return MusicInfo{HasMedia: false}
	}
	parts := strings.Split(out, ", ")
	info := MusicInfo{HasMedia: true, Playing: true}
	if len(parts) > 0 {
		info.Title = parts[0]
	}
	if len(parts) > 1 {
		info.Artist = parts[1]
	}
	return info
}

// ControlMusic controls the macOS Music app.
func ControlMusic(action string) bool {
	var cmd string
	switch action {
	case ActionPlay:
		cmd = `tell application "Music" to play`
	case ActionPause:
		cmd = `tell application "Music" to pause`
	case ActionPlayPause:
		cmd = `tell application "Music" to playpause`
	case ActionNext:
		cmd = `tell application "Music" to next track`
	case ActionPrevious:
		cmd = `tell application "Music" to previous track`
	default:
		return false
	}
	_, ok := osascript(cmd)
	return ok
}

// MusicDiagnostics 返回最近一次查询的错误（Windows 用它做诊断；这里无缓存）。
func MusicDiagnostics() string { return "" }
