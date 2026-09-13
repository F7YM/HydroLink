// Package applog writes the app's log to a file next to the data directory.
//
// 起因：Windows 版改成 GUI 子系统（-H windowsgui）之后就没有控制台了，
// stdout/stderr 无处可去，出问题将完全不可观测。所以日志必须同时落文件。
package applog

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sync"
)

const (
	// MaxSize 是单个日志文件的上限，超过就轮转成 .1（只留一份历史）。
	MaxSize = 1 << 20 // 1 MiB
	// FileName 是数据目录下的日志文件名。
	FileName = "hydrolink.log"
)

var (
	mu     sync.Mutex
	file   *os.File
	path   string
	stdout io.Writer = os.Stdout
)

// Init opens <dir>/hydrolink.log (rotating it when it grew too large) and sends
// the standard logger to both stderr and that file. A failure is not fatal:
// the caller keeps running with console logging only, and the error is
// reported to stderr so the user can see why.
func Init(dir string) error {
	if dir == "" {
		return fmt.Errorf("数据目录为空")
	}
	path = filepath.Join(dir, FileName)
	if err := rotateIfNeeded(); err != nil {
		return err
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return err
	}
	mu.Lock()
	file = f
	mu.Unlock()
	log.SetOutput(io.MultiWriter(os.Stderr, f))
	return nil
}

// Stdout returns the writer used for the in-app log buffer: stdout plus the log
// file when file logging is active (stdout alone otherwise, so a GUI build
// without a console simply discards it).
func Stdout() io.Writer {
	mu.Lock()
	f := file
	mu.Unlock()
	if f == nil {
		return stdout
	}
	return io.MultiWriter(stdout, f)
}

// Path returns the log file path ("" when file logging is not active).
func Path() string {
	mu.Lock()
	defer mu.Unlock()
	if file == nil {
		return ""
	}
	return path
}

// Close flushes and closes the log file.
func Close() {
	mu.Lock()
	f := file
	file = nil
	mu.Unlock()
	if f != nil {
		_ = f.Sync()
		_ = f.Close()
	}
}

// rotateIfNeeded renames an oversized log to <name>.1 (replacing the previous
// history file). Missing file is not an error.
func rotateIfNeeded() error {
	info, err := os.Stat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	if info.Size() < MaxSize {
		return nil
	}
	_ = os.Remove(path + ".1")
	return os.Rename(path, path+".1")
}
