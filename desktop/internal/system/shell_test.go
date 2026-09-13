package system

import (
	"runtime"
	"strings"
	"testing"
)

// RunCommand 会设置平台相关的 SysProcAttr（Windows 上隐藏控制台窗口），
// 这里确保它没有把输出捕获或退出码弄坏。
func TestRunCommandCapturesOutput(t *testing.T) {
	script := "echo hello-shell"
	if runtime.GOOS == "windows" {
		script = "echo hello-shell"
	}
	res := RunCommand(script)
	if !res.Success {
		t.Fatalf("命令应当成功: %+v", res)
	}
	if !strings.Contains(res.Output, "hello-shell") {
		t.Fatalf("输出未捕获: %q", res.Output)
	}
	if res.ExitCode != 0 {
		t.Fatalf("退出码应为 0: %d", res.ExitCode)
	}
}

func TestRunCommandNonZeroExit(t *testing.T) {
	script := "exit 3"
	if runtime.GOOS == "windows" {
		script = "exit 3"
	}
	res := RunCommand(script)
	if res.Success {
		t.Fatalf("非零退出不应算成功: %+v", res)
	}
	if res.ExitCode != 3 {
		t.Fatalf("退出码应为 3，实际 %d", res.ExitCode)
	}
}

// 非 Windows 平台不应设置子进程窗口属性（Linux 没有"控制台窗口"概念）。
func TestHideWindowAttrIsNilOffWindows(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows 上由平台文件实现")
	}
	if attr := hideWindowAttr(); attr != nil {
		t.Fatalf("非 Windows 平台应为 nil，实际 %+v", attr)
	}
}
