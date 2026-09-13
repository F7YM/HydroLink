//go:build !linux

// Package tray: non-Linux builds have no tray implementation yet; Settings
// still exposes the toggle and reports why the tray is unavailable.
package tray

import "fmt"

// Options configures the tray icon.
type Options struct {
	IconPNG    []byte
	Title      string
	Tooltip    string
	Subtitle   string
	Logf       func(format string, args ...any)
	OnActivate func()
	OnQuit     func()
}

// Tray is a registered tray icon (unimplemented off Linux).
type Tray struct{}

// Start always fails on platforms without an implementation.
func Start(opts Options) (*Tray, error) {
	return nil, fmt.Errorf("当前平台暂不支持托盘图标")
}

// Stop is a no-op.
func (t *Tray) Stop() {}

// Running always reports false.
func (t *Tray) Running() bool { return false }
