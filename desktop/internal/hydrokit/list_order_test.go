package hydrokit

import "testing"

// 插件列表顺序必须稳定：rt.plugins 是 map，不排序的话 UI 每 2 秒拉一次就会看到
// 插件顺序乱跳（真机上表现为「有时候插件列表顺序会莫名其妙变」）。
func TestListIsSortedByPackageID(t *testing.T) {
	rt := NewRuntime(nil)
	for _, id := range []string{"com.z.last", "com.a.first", "com.m.middle"} {
		m := Manifest{
			Package: Package{ID: id, Name: id, Version: "0.1.0"},
			Author:  "测试",
			Apps:    []Item{{ID: "main", Name: "主页"}},
		}
		if err := rt.Register(NewJSPlugin(&m, NewWorkerBridge())); err != nil {
			t.Fatalf("register %s: %v", id, err)
		}
	}

	var first []string
	for round := 0; round < 20; round++ {
		var got []string
		for _, s := range rt.List() {
			got = append(got, s.Package.ID)
		}
		if round == 0 {
			first = got
			continue
		}
		for i := range got {
			if got[i] != first[i] {
				t.Fatalf("round %d: list = %v, want %v (顺序不稳定)", round, got, first)
			}
		}
	}
	want := []string{"com.a.first", "com.m.middle", "com.z.last"}
	for i := range want {
		if first[i] != want[i] {
			t.Fatalf("list = %v, want %v (要按包 id 排序)", first, want)
		}
	}
}
