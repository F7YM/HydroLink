package main

import "testing"

// 回归：窗口必须由 Wails 显示（StartHidden=false）。
//
// 曾经为了让"第二次启动不闪窗口"而开启 StartHidden，结果 Windows 上窗口永远
// 不显示（Wails 在 onDomReady 里 `if StartHidden { return }`，而 OnStartup 是
// 并发协程、早于窗口就绪，从那里 WindowShow 无效）——托盘关掉后毫无入口，
// 表现为"打开就闪退、第二次才行"。第二实例不闪窗口靠的是 Wails 自己：
// SetupSingleInstance 在窗口创建之前就退出进程。
func TestWindowIsShownByWails(t *testing.T) {
	if !showWindowOnStartup {
		t.Fatal("showWindowOnStartup 必须为 true（StartHidden 会让窗口永远不显示）")
	}
}
