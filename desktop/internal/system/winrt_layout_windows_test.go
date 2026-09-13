//go:build windows

package system

import (
	"testing"
	"unsafe"
)

// vtable 槽位守护（在 Windows 上跑 `go test ./internal/system/` 即可验证）。
//
// WinRT 接口是扁平结构：每个接口只继承 IInspectable（3 个 IUnknown + 3 个
// IInspectable 槽），`requires` 声明**不会**把基接口方法内联进来——基接口能力要
// 另外 QueryInterface。曾经按经典 COM 继承写，槽位整体偏移，GetSize 调到别的
// 函数指针上，进程直接静默终止（表现为"窗口和托盘一起消失"）。
func TestWinRTVTableLayout(t *testing.T) {
	slot := unsafe.Sizeof(uintptr(0))
	cases := []struct {
		name string
		got  uintptr
		want uintptr
	}{
		// 3 (IUnknown) + 3 (IInspectable) + 1 (Close) = 7
		{"iClosable", unsafe.Sizeof(iClosableVtbl{}), 7 * slot},
		// 3 + 3 + 1 (ReadAsync) = 7
		{"iInputStream", unsafe.Sizeof(iInputStreamVtbl{}), 7 * slot},
		// 3 + 3 + 9 (Size..CanWrite) = 15
		{"iRandomAccessStream", unsafe.Sizeof(iRandomAccessStreamVtbl{}), 15 * slot},
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("%s 槽位数不对: %d 个字段，期望 %d（WinRT 接口是扁平的，基接口不内联）",
				c.name, c.got/slot, c.want/slot)
		}
	}
}
