package system

import "fmt"

// Logf 是 platform 代码向应用日志输出的钩子（由 app.NewServer 注入）。
// 置空时静默。用于"音乐读取到哪一步"这类面包屑：如果进程被硬崩掉，
// 日志里最后一条面包屑就是崩溃点。
var Logf func(format string, args ...any)

// SetLogger 注入日志实现（传 nil 关闭）。
func SetLogger(f func(format string, args ...any)) { Logf = f }

func logf(format string, args ...any) {
	if Logf != nil {
		Logf(format, args...)
	}
}

// logfErr 记录错误（带上下文）。
func logfErr(step string, err error) {
	if err != nil {
		logf("system: %s 失败: %v", step, err)
	}
}

var _ = fmt.Sprintf
