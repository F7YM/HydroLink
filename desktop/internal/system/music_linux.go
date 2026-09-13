//go:build linux

package system

import (
	"os/exec"
	"strings"
)

func runPlayerctl(args ...string) (string, bool) {
	out, err := exec.Command("playerctl", args...).Output()
	if err != nil {
		return "", false
	}
	return strings.TrimSpace(string(out)), true
}

// GetMusic reads media info via playerctl (MPRIS).
func GetMusic() MusicInfo {
	out, ok := runPlayerctl("metadata", "--format", "{{title}}|||{{artist}}|||{{status}}|||{{mpris:artUrl}}")
	if !ok || out == "" {
		return MusicInfo{HasMedia: false}
	}
	parts := strings.Split(out, "|||")
	info := MusicInfo{HasMedia: true}
	if len(parts) > 0 {
		info.Title = parts[0]
	}
	if len(parts) > 1 {
		info.Artist = parts[1]
	}
	if len(parts) > 2 {
		info.Playing = parts[2] == "Playing"
	}
	if len(parts) > 3 {
		info.ArtURL = parts[3]
	}
	return info
}

// ControlMusic maps the HydroLink actions to playerctl commands.
func ControlMusic(action string) bool {
	var args []string
	switch action {
	case ActionPlay:
		args = []string{"play"}
	case ActionPause:
		args = []string{"pause"}
	case ActionPlayPause:
		args = []string{"play-pause"}
	case ActionNext:
		args = []string{"next"}
	case ActionPrevious:
		args = []string{"previous"}
	default:
		return false
	}
	_, ok := runPlayerctl(args...)
	return ok
}

// MusicDiagnostics 返回最近一次查询的错误（Windows 用它做诊断；这里无缓存）。
func MusicDiagnostics() string { return "" }
