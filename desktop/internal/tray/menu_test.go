package tray

import "testing"

// 菜单规格是 Linux/Windows 两端的唯一来源，这里钉住 id 与文案，
// 避免以后只改一端导致两平台菜单不一致。
func TestMenuEntries(t *testing.T) {
	want := []MenuEntry{
		{ID: 1, Label: "显示主窗口"},
		{ID: 2, Separator: true},
		{ID: 3, Label: "退出"},
	}
	got := MenuEntries()
	if len(got) != len(want) {
		t.Fatalf("菜单项数量 = %d, 期望 %d", len(got), len(want))
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("第 %d 项 = %+v, 期望 %+v", i, got[i], want[i])
		}
	}
	if MenuIDShow != want[0].ID || MenuIDSep != want[1].ID || MenuIDQuit != want[2].ID {
		t.Error("菜单 id 常量与规格不一致")
	}
	if DefaultTitle == "" || DefaultTooltip == "" {
		t.Error("默认标题/提示不应为空")
	}
}

// 托盘回调 panic 不能连累进程（回调里跑的是 Wails 窗口操作）。
func TestSafeCallRecovers(t *testing.T) {
	done := make(chan struct{})
	safeCall("panic-test", func() {
		defer close(done)
		panic("boom")
	})
	<-done // 进程还活着就说明 recover 生效
	safeCall("nil-test", nil)
}
