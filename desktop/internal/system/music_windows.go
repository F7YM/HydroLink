//go:build windows

package system

// GetMusic on Windows reports no active media session for now. The Windows
// SMTC (System Media Transport Controls) integration via go-ole is a
// follow-up; until then the watch shows no music, matching has_media=false.
func GetMusic() MusicInfo {
	return MusicInfo{HasMedia: false}
}

// ControlMusic on Windows is not yet implemented.
func ControlMusic(action string) bool {
	return false
}
