//go:build windows

package system

import (
	"fmt"
	"runtime"
	"sync"

	"github.com/go-ole/go-ole"
)

// WinRT 调用必须固定在**一个专属的 MTA 线程**上执行。
//
// 踩过的坑（Windows 真机实测）：
//   - apartment（线程模态）一旦设定就不能改。进程里别的包可能已经把某些线程设成
//     STA；在 STA 线程上发起 WinRT 异步操作时，完成回调要投递到该 STA 的消息队列，
//     而 Go 侧不泵消息 —— IAsyncInfo::GetStatus 永远不是 Completed，表现为
//     "WinRT 调用超时"（真机上取封面就是这样 1.5s 超时）。
//   - 反过来，把别的包（如通知库）需要的 STA 线程设成 MTA，会让它们报
//     RPC_E_CHANGED_MODE（Windows 上系统通知曾因此彻底失效）。
//
// 所以：起一个常驻 goroutine、LockOSThread 并初始化为 MTA，所有 WinRT 调用都
// 串行排队到它上面 —— 线程模态由我们决定，也顺带避免了 COM 并发问题。

var (
	winrtOnce sync.Once
	winrtReqs = make(chan func())
	winrtErr  error
)

func startWinRTThread() {
	ready := make(chan struct{})
	go func() {
		runtime.LockOSThread() // 该线程归我们所有，直到进程结束
		if err := ole.RoInitialize(1 /* RO_INIT_MULTITHREADED */); err != nil {
			winrtErr = fmt.Errorf("WinRT 线程初始化失败: %v", err)
			logf("system: %v", winrtErr)
		}
		close(ready)
		for fn := range winrtReqs {
			fn()
		}
	}()
	<-ready
}

// runWinRT 把一次 WinRT 调用排到专属 MTA 线程上执行。
func runWinRT[T any](fn func() (T, error)) (T, error) {
	winrtOnce.Do(startWinRTThread)
	var zero T
	if winrtErr != nil {
		return zero, winrtErr
	}
	type outcome struct {
		v   T
		err error
	}
	ch := make(chan outcome, 1)
	winrtReqs <- func() {
		v, err := fn()
		ch <- outcome{v: v, err: err}
	}
	r := <-ch
	return r.v, r.err
}
