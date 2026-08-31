// Package system: music.go defines the cross-platform music types and
// actions; each platform file provides the implementation.

package system

// MusicInfo mirrors Android's music response fields.
type MusicInfo struct {
	HasMedia bool
	Title    string
	Artist   string
	Playing  bool
}

// MusicControl actions supported by /api/music/control.
const (
	ActionPlay      = "play"
	ActionPause     = "pause"
	ActionPlayPause = "playpause"
	ActionNext      = "next"
	ActionPrevious  = "previous"
)

// GetMusic returns the active media info.
// Implemented per platform (music_linux.go / music_darwin.go / music_windows.go).
// ControlMusic sends a play/pause/next/previous action; implemented per platform.
