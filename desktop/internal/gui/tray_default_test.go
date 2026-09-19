package gui

import (
	"testing"

	"hydrolink-desktop/internal/store"
)

// 托盘默认开启：没配置过（nil）也算开；显式 false 才是用户关掉了。
func TestTrayDefaultsToOn(t *testing.T) {
	on := true
	off := false
	cases := []struct {
		name string
		cfg  store.Config
		want bool
	}{
		{"老配置没有这个键", store.Config{}, true},
		{"显式关闭", store.Config{TrayEnabled: &off}, false},
		{"显式开启", store.Config{TrayEnabled: &on}, true},
	}
	for _, c := range cases {
		if got := trayEnabledFrom(c.cfg); got != c.want {
			t.Errorf("%s: trayEnabledFrom = %v, 期望 %v", c.name, got, c.want)
		}
	}
}
