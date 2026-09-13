//go:build windows

package system

import (
	"fmt"
	"os"
	"time"
	"unsafe"

	"github.com/go-ole/go-ole"
	"github.com/saltosystems/winrt-go/windows/foundation"
	"github.com/saltosystems/winrt-go/windows/media/control"
)

// Windows 音乐：走 WinRT 的 SMTC（System Media Transport Controls），也就是
// 系统音量弹窗里那套"当前媒体会话"。不使用外部进程（PowerShell 等）。
//
// 纯 Go 的 WinRT 投影来自 github.com/saltosystems/winrt-go；异步操作通过
// IAsyncInfo::get_Status 轮询到 Completed 后取结果 —— 这样不必为每个泛型参数
// 手写 AsyncOperationCompletedHandler 的 IID。

const (
	// smtcTimeout 是单次 WinRT 调用的上限，避免 HTTP 请求被卡住。
	smtcTimeout = 1500 * time.Millisecond
	// smtcPollInterval 是异步状态轮询间隔。
	smtcPollInterval = 2 * time.Millisecond
	// musicCacheTTL 让手表的高频轮询不会每次都跨进程调用 WinRT。
	musicCacheTTL = 2 * time.Second

	// 线程初始化标志（RO_INIT_MULTITHREADED）。
	roInitMultithreaded = 1
	// 已初始化 / 线程模式已固定：这两种"错误"可以直接忽略。
	hrSFalse         = 0x00000001
	hrRPCChangedMode = 0x80010106
)

var winMusic = newCachedMusicBackend(&smtcBackend{}, musicCacheTTL)

// GetMusic 读取当前媒体会话（标题/艺术家/播放状态/封面）。
func GetMusic() MusicInfo {
	return winMusic.Get()
}

// ControlMusic 通过 SMTC 控制当前会话。
func ControlMusic(action string) bool {
	return winMusic.Control(action)
}

// MusicDiagnostics 返回最近一次查询的错误文本（由 /api/music 记入日志）。
func MusicDiagnostics() string {
	if err := winMusic.LastError(); err != nil {
		return err.Error()
	}
	return ""
}

type smtcBackend struct{}

func (s *smtcBackend) read() (MusicInfo, error) {
	// 整体排到专属 MTA 线程（见 winrt_thread_windows.go）。
	return runWinRT(func() (MusicInfo, error) { return s.readOnWinRTThread() })
}

func (s *smtcBackend) readOnWinRTThread() (MusicInfo, error) {
	sess, err := currentSession()
	if err != nil {
		logf("music: 获取 SMTC 会话失败: %v", err)
		return MusicInfo{}, err
	}
	if sess == nil {
		return MusicInfo{HasMedia: false}, nil
	}
	props, err := mediaProperties(sess)
	if err != nil {
		logf("music: 读取媒体属性失败: %v", err)
		return MusicInfo{}, err
	}
	if props == nil {
		return MusicInfo{HasMedia: false}, nil
	}
	title, _ := props.GetTitle()
	artist, _ := props.GetArtist()
	info := MusicInfo{
		HasMedia: true,
		Title:    title,
		Artist:   artist,
		Playing:  trackingPlaying(sess),
	}
	// 封面读取失败不影响标题/艺术家（很多曲目本来就没有封面）。
	if art, err := coverArtURL(props); err == nil {
		info.ArtURL = art
	} else if key := title + "|" + artist; key != lastNoCoverKey {
		// 每首曲子只提示一次，避免 2 秒一轮的轮询把日志刷满。
		lastNoCoverKey = key
		logf("music: 封面不可用（%q）: %v", title, err)
	}
	return info, nil
}

// lastNoCoverKey 记录上一次"没有封面"的曲目，用于去重日志。
var lastNoCoverKey string

func (s *smtcBackend) control(action string) (bool, error) {
	return runWinRT(func() (bool, error) { return s.controlOnWinRTThread(action) })
}

func (s *smtcBackend) controlOnWinRTThread(action string) (bool, error) {
	sess, err := currentSession()
	if err != nil {
		return false, err
	}
	if sess == nil {
		return false, fmt.Errorf("当前没有媒体会话")
	}
	var (
		op   *foundation.IAsyncOperation
		cerr error
	)
	switch action {
	case ActionPlay:
		op, cerr = sess.TryPlayAsync()
	case ActionPause:
		op, cerr = sess.TryPauseAsync()
	case ActionPlayPause:
		op, cerr = sess.TryTogglePlayPauseAsync()
	case ActionNext:
		op, cerr = sess.TrySkipNextAsync()
	case ActionPrevious:
		op, cerr = sess.TrySkipPreviousAsync()
	default:
		return false, fmt.Errorf("不支持的操作: %s", action)
	}
	if cerr != nil {
		return false, cerr
	}
	return awaitOK(op)
}

// ---- WinRT 辅助 ----

// roInitialize 做每线程的 MTA 初始化。Go 的 goroutine 会在线程间迁移，
// 所以每次进入 WinRT 前都调用（已初始化时返回 S_FALSE，开销极小）。
// roInitialize 只作为兜底：正常路径上专属线程已经初始化过（再调会返回 S_FALSE）。
func roInitialize() error {
	err := ole.RoInitialize(roInitMultithreaded)
	if err == nil {
		return nil
	}
	if oleErr, ok := err.(*ole.OleError); ok && uint32(oleErr.Code()) == hrSFalse {
		return nil // 已经初始化（S_FALSE）
	}
	return fmt.Errorf("RoInitialize 失败: %v", err)
}

// queryInterface 把 WinRT 返回的 IInspectable 指针换成投影类型指针。
func queryInterface(ptr unsafe.Pointer, signature string) (unsafe.Pointer, error) {
	if ptr == nil {
		return nil, fmt.Errorf("空接口指针（%s）", signature)
	}
	unk := (*ole.IUnknown)(ptr)
	out, err := unk.QueryInterface(ole.NewGUID(signature))
	if err != nil {
		return nil, fmt.Errorf("QueryInterface %s 失败: %v", signature, err)
	}
	return unsafe.Pointer(out), nil
}

// await 轮询 IAsyncInfo 直到完成并返回结果指针。
func await(op *foundation.IAsyncOperation) (unsafe.Pointer, error) {
	if op == nil {
		return nil, fmt.Errorf("异步操作为空")
	}
	qi, err := op.QueryInterface(ole.NewGUID(foundation.GUIDIAsyncInfo))
	if err != nil {
		return nil, fmt.Errorf("查询 IAsyncInfo 失败: %v", err)
	}
	ai := (*foundation.IAsyncInfo)(unsafe.Pointer(qi))
	deadline := time.Now().Add(smtcTimeout)
	for {
		status, err := ai.GetStatus()
		if err != nil {
			return nil, err
		}
		switch status {
		case foundation.AsyncStatusCompleted:
			return op.GetResults()
		case foundation.AsyncStatusCanceled:
			return nil, fmt.Errorf("WinRT 异步操作被取消")
		case foundation.AsyncStatusError:
			code, _ := ai.GetErrorCode()
			return nil, fmt.Errorf("WinRT 异步操作失败: HRESULT 0x%08x", code.Value)
		}
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("WinRT 调用超时（%s）", smtcTimeout)
		}
		time.Sleep(smtcPollInterval)
	}
}

// awaitOK 处理 Try*Async() 这类 IAsyncOperation<bool>：等待完成即视为成功。
// （不去解析 IReference<bool>：ABI 里的 Boolean 宽度容易踩坑，而"await 没报错"
// 已经足够表达"命令被接受"，真实状态由随后的读取反映。）
func awaitOK(op *foundation.IAsyncOperation) (bool, error) {
	if _, err := await(op); err != nil {
		return false, err
	}
	return true, nil
}

// currentSession 返回当前媒体会话（没有活跃会话时返回 nil, nil）。
func currentSession() (*control.GlobalSystemMediaTransportControlsSession, error) {
	if err := roInitialize(); err != nil {
		return nil, err
	}
	op, err := control.GlobalSystemMediaTransportControlsSessionManagerRequestAsync()
	if err != nil {
		return nil, fmt.Errorf("获取 SMTC 管理器失败: %v", err)
	}
	ptr, err := await(op)
	if err != nil {
		return nil, err
	}
	qi, err := queryInterface(ptr, control.SignatureiGlobalSystemMediaTransportControlsSessionManager)
	if err != nil {
		return nil, err
	}
	mgr := (*control.GlobalSystemMediaTransportControlsSessionManager)(qi)
	sess, err := mgr.GetCurrentSession()
	if err != nil {
		return nil, fmt.Errorf("读取当前媒体会话失败: %v", err)
	}
	return sess, nil
}

// mediaProperties 取当前曲目的标题/艺术家/封面引用。
func mediaProperties(sess *control.GlobalSystemMediaTransportControlsSession) (*control.GlobalSystemMediaTransportControlsSessionMediaProperties, error) {
	op, err := sess.TryGetMediaPropertiesAsync()
	if err != nil {
		return nil, fmt.Errorf("读取媒体属性失败: %v", err)
	}
	ptr, err := await(op)
	if err != nil {
		return nil, err
	}
	qi, err := queryInterface(ptr, control.SignatureiGlobalSystemMediaTransportControlsSessionMediaProperties)
	if err != nil {
		return nil, err
	}
	return (*control.GlobalSystemMediaTransportControlsSessionMediaProperties)(qi), nil
}

// trackingPlaying 读取播放状态（只有 Playing 才算 true）。
func trackingPlaying(sess *control.GlobalSystemMediaTransportControlsSession) bool {
	if sess == nil {
		return false
	}
	info, err := sess.GetPlaybackInfo()
	if err != nil || info == nil {
		// 没有播放信息是正常情况（例如会话刚建立），不能当错误也不能解引用。
		return false
	}
	status, err := info.GetPlaybackStatus()
	if err != nil {
		return false
	}
	return status == control.GlobalSystemMediaTransportControlsSessionPlaybackStatusPlaying
}

// coverArtURL 把 SMTC 的缩略图读成临时文件并返回 file:// URL。
func coverArtURL(props *control.GlobalSystemMediaTransportControlsSessionMediaProperties) (string, error) {
	if props == nil {
		return "", fmt.Errorf("媒体属性为空")
	}
	ref, err := props.GetThumbnail()
	if err != nil {
		return "", fmt.Errorf("读取封面引用失败: %v", err)
	}
	if ref == nil {
		return "", fmt.Errorf("当前曲目没有封面")
	}
	data, err := readStreamReference(ref)
	if err != nil {
		return "", fmt.Errorf("读取封面数据失败: %v", err)
	}
	if len(data) == 0 {
		return "", fmt.Errorf("封面数据为空")
	}
	path, err := coverArtPath(data)
	if err != nil {
		return "", err
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return "", err
	}
	return fileURL(path), nil
}
