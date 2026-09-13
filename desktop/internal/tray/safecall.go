package tray

import "runtime/debug"

// safeCall 在 goroutine 里跑托盘回调并兜住 panic。
//
// 托盘回调来自消息泵/DBus 线程：那里 panic 会直接终止进程，而回调里通常是
// Wails 的窗口操作（WindowShow/Quit）—— 一次失败不应该让整个服务端消失。
// 兜底写 stderr（GUI 子系统看不到控制台，但至少不会连累进程）。
func safeCall(name string, fn func()) {
	if fn == nil {
		return
	}
	go func() {
		defer func() {
			if r := recover(); r != nil {
				println("tray: 回调 panic (" + name + "): " + string(debug.Stack()))
			}
		}()
		fn()
	}()
}
