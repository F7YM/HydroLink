//go:build windows

package system

import (
	"fmt"
	"syscall"
	"time"
	"unsafe"

	"github.com/go-ole/go-ole"
	"github.com/saltosystems/winrt-go/windows/foundation"
	"github.com/saltosystems/winrt-go/windows/storage/streams"
)

// 手写的 WinRT 流互操作：读 SMTC 缩略图（IRandomAccessStreamReference）。
// winrt-go 只生成了 IRandomAccessStreamReference / IBuffer / DataReader，缺
// IRandomAccessStream 与 IInputStream，所以这两个接口要自己声明。
//
// **关键：WinRT 接口是"扁平"的** —— 每个接口只继承 IInspectable，`requires`
// 只是"实现类还必须实现这些接口"的约束，**基接口的方法不会内联进 vtable**。
// （曾经按经典 COM 继承去写，槽位整体偏移，GetSize 实际调到别的函数指针上 →
// 调用野地址 → 进程静默终止。教训记在这里。）
//
// 因此这里的 vtable 一律是 IInspectable_Vtbl + 该接口自己的方法，基接口能力
// （Close / ReadAsync）改由**另外 QueryInterface** 到对应接口后调用。
//
// IID 取自 Windows SDK 的 windows.storage.streams.idl，并与 windows-rs 的生成
// 代码逐字核对过（define_interface! 行）。

const (
	// IClosable：{30d5a829-7fa4-4026-83bb-d75bae4ea99e}
	iidIClosable = "{30d5a829-7fa4-4026-83bb-d75bae4ea99e}"
	// IInputStream：{905a0fe2-bc53-11df-8c49-001e4fc686da}（ReadAsync 在槽 6）
	iidIInputStream = "{905a0fe2-bc53-11df-8c49-001e4fc686da}"
	// IRandomAccessStream：{905a0fe1-bc53-11df-8c49-001e4fc686da}
	iidIRandomAccessStream = "{905a0fe1-bc53-11df-8c49-001e4fc686da}"
	// IRandomAccessStreamWithContentType：{cc254827-4b3d-438f-9232-10c76bc7e038}
	iidIRandomAccessStreamWithContentType = "{cc254827-4b3d-438f-9232-10c76bc7e038}"

	// maxCoverBytes 防止异常流把内存吃满（封面不可能有几十 MB）。
	maxCoverBytes = 16 << 20
)

// iClosable：IInspectable + Close（槽 6）。
type iClosable struct{ ole.IUnknown }

type iClosableVtbl struct {
	ole.IInspectableVtbl
	Close uintptr // 6
}

func (v *iClosable) VTable() *iClosableVtbl {
	return (*iClosableVtbl)(unsafe.Pointer(v.RawVTable))
}

func (v *iClosable) Close() error {
	hr, _, _ := syscall.SyscallN(v.VTable().Close, uintptr(unsafe.Pointer(v)))
	if hr != 0 {
		return ole.NewError(hr)
	}
	return nil
}

// iInputStream：IInspectable + ReadAsync（槽 6）。
type iInputStream struct{ ole.IUnknown }

type iInputStreamVtbl struct {
	ole.IInspectableVtbl
	ReadAsync uintptr // 6
}

func (v *iInputStream) VTable() *iInputStreamVtbl {
	return (*iInputStreamVtbl)(unsafe.Pointer(v.RawVTable))
}

// ReadAsync(buffer, count, options) → IAsyncOperationWithProgress<IBuffer, u32>
func (v *iInputStream) ReadAsync(buffer *streams.IBuffer, count uint32, options int32) (*ole.IUnknown, error) {
	var out *ole.IUnknown
	hr, _, _ := syscall.SyscallN(
		v.VTable().ReadAsync,
		uintptr(unsafe.Pointer(v)),
		uintptr(unsafe.Pointer(buffer)),
		uintptr(count),
		uintptr(options),
		uintptr(unsafe.Pointer(&out)),
	)
	if hr != 0 {
		return nil, ole.NewError(hr)
	}
	return out, nil
}

// iRandomAccessStream：IInspectable + 自己的 9 个方法（槽 6..14）。
type iRandomAccessStream struct{ ole.IUnknown }

type iRandomAccessStreamVtbl struct {
	ole.IInspectableVtbl
	GetSize           uintptr // 6
	SetSize           uintptr // 7
	GetInputStreamAt  uintptr // 8
	GetOutputStreamAt uintptr // 9
	GetPosition       uintptr // 10
	Seek              uintptr // 11
	CloneStream       uintptr // 12
	GetCanRead        uintptr // 13
	GetCanWrite       uintptr // 14
}

func (v *iRandomAccessStream) VTable() *iRandomAccessStreamVtbl {
	return (*iRandomAccessStreamVtbl)(unsafe.Pointer(v.RawVTable))
}

func (v *iRandomAccessStream) GetSize() (uint64, error) {
	var out uint64
	hr, _, _ := syscall.SyscallN(
		v.VTable().GetSize,
		uintptr(unsafe.Pointer(v)),
		uintptr(unsafe.Pointer(&out)),
	)
	if hr != 0 {
		return 0, ole.NewError(hr)
	}
	return out, nil
}

func (v *iRandomAccessStream) GetInputStreamAt(position uint64) (*iInputStream, error) {
	var out *iInputStream
	hr, _, _ := syscall.SyscallN(
		v.VTable().GetInputStreamAt,
		uintptr(unsafe.Pointer(v)),
		uintptr(position),
		uintptr(unsafe.Pointer(&out)),
	)
	if hr != 0 {
		return nil, ole.NewError(hr)
	}
	if out == nil {
		return nil, fmt.Errorf("GetInputStreamAt 返回空指针")
	}
	return out, nil
}

// closeWith 把任意 IInspectable 指针按 IClosable 关掉（失败只记日志）。
func closeWith(ptr unsafe.Pointer) {
	if ptr == nil {
		return
	}
	qi, err := queryInterface(ptr, iidIClosable)
	if err != nil {
		return
	}
	_ = (*iClosable)(qi).Close()
}

// readStreamReference 把 IRandomAccessStreamReference 的内容读成字节。
func readStreamReference(ref *streams.IRandomAccessStreamReference) ([]byte, error) {
	if err := roInitialize(); err != nil {
		return nil, err
	}
	op, err := ref.OpenReadAsync()
	if err != nil {
		return nil, fmt.Errorf("OpenReadAsync 失败: %v", err)
	}
	ptr, err := await(op)
	if err != nil {
		return nil, err
	}
	defer closeWith(ptr)

	// 流本体：QI 到 IRandomAccessStream（WithContentType 只有 ContentType 一个方法）
	streamQI, err := queryInterface(ptr, iidIRandomAccessStream)
	if err != nil {
		return nil, err
	}
	stream := (*iRandomAccessStream)(streamQI)

	size, err := stream.GetSize()
	if err != nil {
		return nil, fmt.Errorf("读取封面流大小失败: %v", err)
	}
	if size == 0 {
		return nil, fmt.Errorf("封面流为空")
	}
	if size > maxCoverBytes {
		return nil, fmt.Errorf("封面过大: %d 字节", size)
	}

	in, err := stream.GetInputStreamAt(0)
	if err != nil {
		return nil, fmt.Errorf("获取输入流失败: %v", err)
	}
	// IInputStream 也需要单独关闭（它不是 IRandomAccessStream 的基接口）
	defer func() {
		if qi, err := in.QueryInterface(ole.NewGUID(iidIClosable)); err == nil {
			_ = (*iClosable)(unsafe.Pointer(qi)).Close()
		}
	}()

	buffer, err := streams.BufferCreate(uint32(size))
	if err != nil {
		return nil, fmt.Errorf("分配缓冲失败: %v", err)
	}
	ibuf := ibufferOf(buffer)
	if ibuf == nil {
		return nil, fmt.Errorf("Buffer 转 IBuffer 失败")
	}

	readOp, err := in.ReadAsync(ibuf, uint32(size), 0 /* InputStreamOptions.None */)
	if err != nil {
		return nil, fmt.Errorf("ReadAsync 失败: %v", err)
	}
	defer readOp.Release()

	if err := awaitIUnknown(readOp); err != nil {
		return nil, err
	}

	n, err := buffer.GetLength()
	if err != nil {
		return nil, fmt.Errorf("读取缓冲长度失败: %v", err)
	}
	if n == 0 {
		return nil, fmt.Errorf("封面读取结果为 0 字节")
	}

	reader, err := streams.DataReaderFromBuffer(ibuf)
	if err != nil {
		return nil, fmt.Errorf("创建 DataReader 失败: %v", err)
	}
	defer reader.Release()
	data, err := reader.ReadBytes(n)
	if err != nil {
		return nil, fmt.Errorf("读取封面字节失败: %v", err)
	}
	return data, nil
}

// ibufferOf 把 Buffer 实例 QI 成 IBuffer 接口指针（库的工厂方法需要它）。
func ibufferOf(buffer *streams.Buffer) *streams.IBuffer {
	if buffer == nil {
		return nil
	}
	itf, err := buffer.QueryInterface(ole.NewGUID(streams.GUIDIBuffer))
	if err != nil {
		return nil
	}
	return (*streams.IBuffer)(unsafe.Pointer(itf))
}

// awaitIUnknown 等待一个 IAsyncInfo（ReadAsync 返回的是带进度的异步操作，
// 库没有为它生成类型，这里只用到 IAsyncInfo 的等待语义）。
func awaitIUnknown(op *ole.IUnknown) error {
	if op == nil {
		return fmt.Errorf("异步操作为空")
	}
	qi, err := op.QueryInterface(ole.NewGUID(foundation.GUIDIAsyncInfo))
	if err != nil {
		return fmt.Errorf("查询 IAsyncInfo 失败: %v", err)
	}
	ai := (*foundation.IAsyncInfo)(unsafe.Pointer(qi))
	deadline := time.Now().Add(smtcTimeout)
	for {
		status, err := ai.GetStatus()
		if err != nil {
			return err
		}
		switch status {
		case foundation.AsyncStatusCompleted:
			return nil
		case foundation.AsyncStatusCanceled:
			return fmt.Errorf("读取封面被取消")
		case foundation.AsyncStatusError:
			code, _ := ai.GetErrorCode()
			return fmt.Errorf("读取封面失败: HRESULT 0x%08x", code.Value)
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("读取封面超时（%s）", smtcTimeout)
		}
		time.Sleep(smtcPollInterval)
	}
}
