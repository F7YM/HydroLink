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

// 手写的 WinRT 流互操作：winrt-go 只生成了 IRandomAccessStreamReference /
// IBuffer / DataReader，缺 IRandomAccessStreamWithContentType 与 IInputStream，
// 而 SMTC 的封面正是 IRandomAccessStreamReference，必须把流读出来。
//
// IID 取值来自 Windows SDK 的 windows.storage.streams.idl（与 mingw-w64 头文件
// 一致），不是凭记忆：
//   IInputStream            905a0fe2-bc53-11df-8c49-001e4fc686da
//   IRandomAccessStream     905a0fe1-bc53-11df-8c49-001e4fc686da
//   IRandomAccessStreamWithContentType
//                           cc254827-4b3d-438f-9232-10c76bc7e038
//
// vtable 槽位（WinRT 接口按 requires 顺序内联基接口方法）：
//   IInspectable 0-5, IClosable.Close 6, IInputStream.ReadAsync 7,
//   IOutputStream.WriteAsync 8 / FlushAsync 9,
//   IRandomAccessStream: 10 get_Size, 11 put_Size, 12 GetInputStreamAt,
//   13 GetOutputStreamAt, 14 get_Position, 15 Seek, 16 CloneStream,
//   17 get_CanRead, 18 get_CanWrite,
//   IRandomAccessStreamWithContentType: 19 get_ContentType

const (
	iidIInputStream = "{905a0fe2-bc53-11df-8c49-001e4fc686da}"
	// iidIRandomAccessStreamWithContentType 是我们要 QI 到的接口。
	iidIRandomAccessStreamWithContentType = "{cc254827-4b3d-438f-9232-10c76bc7e038}"

	// maxCoverBytes 防止异常流把内存吃满（封面不可能有几十 MB）。
	maxCoverBytes = 16 << 20
)

type iInputStream struct{ ole.IUnknown }

type iInputStreamVtbl struct {
	ole.IUnknownVtbl
	Close     uintptr // 6
	ReadAsync uintptr // 7
}

func (v *iInputStream) VTable() *iInputStreamVtbl {
	return (*iInputStreamVtbl)(unsafe.Pointer(v.RawVTable))
}

// ReadAsync 把数据读进 buffer，返回可等待的异步操作。
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

func (v *iInputStream) Close() error {
	hr, _, _ := syscall.SyscallN(v.VTable().Close, uintptr(unsafe.Pointer(v)))
	if hr != 0 {
		return ole.NewError(hr)
	}
	return nil
}

type iRandomAccessStreamWithContentType struct{ ole.IUnknown }

type iRandomAccessStreamWithContentTypeVtbl struct {
	ole.IUnknownVtbl
	Close             uintptr // 6  IClosable
	ReadAsync         uintptr // 7  IInputStream
	WriteAsync        uintptr // 8  IOutputStream
	FlushAsync        uintptr // 9
	GetSize           uintptr // 10
	PutSize           uintptr // 11
	GetInputStreamAt  uintptr // 12
	GetOutputStreamAt uintptr // 13
	GetPosition       uintptr // 14
	Seek              uintptr // 15
	CloneStream       uintptr // 16
	GetCanRead        uintptr // 17
	GetCanWrite       uintptr // 18
	GetContentType    uintptr // 19
}

func (v *iRandomAccessStreamWithContentType) VTable() *iRandomAccessStreamWithContentTypeVtbl {
	return (*iRandomAccessStreamWithContentTypeVtbl)(unsafe.Pointer(v.RawVTable))
}

func (v *iRandomAccessStreamWithContentType) GetSize() (uint64, error) {
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

func (v *iRandomAccessStreamWithContentType) GetInputStreamAt(position uint64) (*iInputStream, error) {
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
	return out, nil
}

func (v *iRandomAccessStreamWithContentType) Close() error {
	hr, _, _ := syscall.SyscallN(v.VTable().Close, uintptr(unsafe.Pointer(v)))
	if hr != 0 {
		return ole.NewError(hr)
	}
	return nil
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
	qi, err := queryInterface(ptr, iidIRandomAccessStreamWithContentType)
	if err != nil {
		return nil, err
	}
	stream := (*iRandomAccessStreamWithContentType)(qi)
	defer stream.Close()

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
	defer in.Close()

	buffer, err := streams.BufferCreate(uint32(size))
	if err != nil {
		return nil, fmt.Errorf("分配缓冲失败: %v", err)
	}
	// InputStreamOptions.None = 0：读满 count 才返回。
	readOp, err := in.ReadAsync(ibufferOf(buffer), uint32(size), 0)
	if err != nil {
		return nil, fmt.Errorf("ReadAsync 失败: %v", err)
	}
	defer readOp.Release()

	if err := awaitIUnknown(readOp); err != nil {
		return nil, err
	}
	// 读到的字节数由 buffer 自身给出（不必再取异步操作的返回值）。
	n, err := buffer.GetLength()
	if err != nil {
		return nil, fmt.Errorf("读取缓冲长度失败: %v", err)
	}
	if n == 0 {
		return nil, fmt.Errorf("封面读取结果为 0 字节")
	}
	reader, err := streams.DataReaderFromBuffer(ibufferOf(buffer))
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
