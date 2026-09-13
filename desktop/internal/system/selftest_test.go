package system

import "testing"

// 诊断开关必须真的生效（它们是 Windows 崩溃二分的唯一手段）。
func TestCoverArtAndNotifySwitches(t *testing.T) {
	if !coverArtOn() {
		t.Fatal("默认应开启封面读取")
	}
	SetCoverArtEnabled(false)
	if coverArtOn() {
		t.Fatal("SetCoverArtEnabled(false) 未生效")
	}
	SetCoverArtEnabled(true)
	if !coverArtOn() {
		t.Fatal("SetCoverArtEnabled(true) 未生效")
	}

	if !notifyEnabled() {
		t.Fatal("默认应开启通知")
	}
	SetNotifyEnabled(false)
	if notifyEnabled() {
		t.Fatal("SetNotifyEnabled(false) 未生效")
	}
	// 禁用后 Notify 必须是安全的空操作（不 panic、不报错）
	if err := Notify("t", "m"); err != nil {
		t.Fatalf("禁用后 Notify 应为空操作: %v", err)
	}
	SetNotifyEnabled(true)
}

// 自检入口在非 Windows 上也要能用（Linux 上验证过）。
func TestMusicSelfTestIsSafe(t *testing.T) {
	info, errText := MusicSelfTest()
	_ = info
	_ = errText // 没有媒体/没有 playerctl 时返回错误文本，但不能 panic
	if _, errs := CoverArtSelfTest(); errs == "" && info.ArtURL == "" {
		// 两者一致：要么都有，要么都没有
		t.Log("no cover available (expected on a silent machine)")
	}
}
