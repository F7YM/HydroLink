package gui

import (
	"fmt"
	"runtime/debug"
)

// safeGo 在独立 goroutine 里执行回调，并兜住 panic。
//
// 背景：**非 HTTP handler 的 goroutine 里 panic 会直接终止进程**（net/http 只
// 保护它自己的 handler goroutine）。而 Windows GUI 子系统没有控制台，panic
// 堆栈写进没人看的 stderr —— 表现就是"窗口和托盘一起凭空消失"。这里把回调包
// 起来，panic 降级成一条带堆栈的日志。
func (a *App) safeGo(name string, fn func()) {
	go func() {
		defer func() {
			if r := recover(); r != nil {
				a.srv.AddLog(fmt.Sprintf("内部错误(%s): %v\n%s", name, r, debug.Stack()), "warning")
			}
		}()
		fn()
	}()
}
