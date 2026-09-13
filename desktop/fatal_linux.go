//go:build linux

package main

import (
	"os/exec"
	"time"
)

// fatalDialog 在 Linux 上尽力弹一个错误框（zenity/kdialog 有就用）。
//
// 从 .desktop 启动时 stdout/stderr 都进了 journal，用户看不到任何提示，
// 表现同样是"点了图标没反应/闪一下就没了"。
func fatalDialog(title, msg string) {
	candidates := [][]string{
		{"zenity", "--error", "--title", title, "--text", msg},
		{"kdialog", "--error", msg, "--title", title},
	}
	for _, argv := range candidates {
		path, err := exec.LookPath(argv[0])
		if err != nil {
			continue
		}
		cmd := exec.Command(path, argv[1:]...)
		done := make(chan struct{})
		if err := cmd.Start(); err != nil {
			continue
		}
		go func() { _ = cmd.Wait(); close(done) }()
		// 用户不点也要能退出，别把进程挂死。
		select {
		case <-done:
		case <-time.After(20 * time.Second):
			_ = cmd.Process.Kill()
		}
		return
	}
}
