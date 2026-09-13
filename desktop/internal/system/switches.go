package system

import "sync/atomic"

// 诊断开关（-no-cover / -no-notify）。
//
// 放在无构建标签的文件里，让所有平台都能编译与单测；Windows 的崩溃排查靠它们
// 做二分：先把可疑项关掉，稳定了再逐个打开。

var (
	coverArtOnFlag atomic.Bool
	notifyOnFlag   atomic.Bool
)

func init() {
	coverArtOnFlag.Store(true)
	notifyOnFlag.Store(true)
}

// coverArtOn 报告是否读取封面缩略图（Windows 的 SMTC 缩略图读取）。
func coverArtOn() bool { return coverArtOnFlag.Load() }

// SetCoverArtEnabled 开关封面读取。
func SetCoverArtEnabled(on bool) {
	coverArtOnFlag.Store(on)
	logf("music: 封面读取 = %v", on)
}

// notifyEnabled 报告是否发送系统通知。
func notifyEnabled() bool { return notifyOnFlag.Load() }

// SetNotifyEnabled 开关系统通知。
func SetNotifyEnabled(on bool) {
	notifyOnFlag.Store(on)
	logf("system: 系统通知 = %v", on)
}
