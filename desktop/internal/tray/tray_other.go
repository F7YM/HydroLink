//go:build !linux && !windows

// Package tray: Linux(StatusNotifierItem) 与 Windows(Shell_NotifyIcon) 之外的
// 平台（当前只有 macOS）还没有实现；设置里的开关仍然存在，并如实报告"托盘不可用"。
package tray

import "fmt"

// Options configures the tray icon.
type Options struct {
	IconPNG    []byte
	IconICO    []byte
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
