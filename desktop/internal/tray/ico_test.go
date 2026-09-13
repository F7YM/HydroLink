package tray

import (
	"os"
	"testing"
)

// 用仓库里真实的 icon.ico 验证解析与选档逻辑。
func TestIconFromRealICO(t *testing.T) {
	data, err := os.ReadFile("../../icon.ico")
	if err != nil {
		t.Skipf("读取 icon.ico 失败: %v", err)
	}
	entries, err := parseICOEntries(data)
	if err != nil {
		t.Fatalf("解析 ICO 目录: %v", err)
	}
	if len(entries) == 0 {
		t.Fatal("ICO 里应当至少有一项")
	}
	for i, e := range entries {
		if e.DataSize == 0 {
			t.Errorf("第 %d 项数据长度为 0", i)
		}
		if e.Width <= 0 || e.Height <= 0 {
			t.Errorf("第 %d 项尺寸非法: %dx%d", i, e.Width, e.Height)
		}
	}

	img, w, h, err := IconFromICO(data)
	if err != nil {
		t.Fatalf("IconFromICO: %v", err)
	}
	if len(img) == 0 {
		t.Fatal("取出的图像数据为空")
	}
	if w != h || w < 16 {
		t.Fatalf("托盘图标应为 >=16 的正方形，实际 %dx%d", w, h)
	}
	t.Logf("选中 %dx%d，%d 字节，共 %d 档", w, h, len(img), len(entries))
}

func TestParseICOErrors(t *testing.T) {
	cases := map[string][]byte{
		"太短":    {0, 0},
		"类型不对":  {0, 0, 2, 0, 1, 0},
		"没有图像":  {0, 0, 1, 0, 0, 0},
		"目录被截断": {0, 0, 1, 0, 1, 0, 32, 32},
		"数据越界":  {0, 0, 1, 0, 1, 0, 32, 32, 0, 0, 0, 0, 99, 0, 0, 0, 10, 0, 0, 0},
	}
	for name, data := range cases {
		if _, err := parseICOEntries(data); err == nil {
			t.Errorf("%s: 应当报错", name)
		}
	}
}

// 选择规则：优先 >=32 的正方形里最小的一档。
func TestPickICOEntry(t *testing.T) {
	entries := []icoEntry{
		{Width: 16, Height: 16, DataSize: 1},
		{Width: 64, Height: 64, DataSize: 2},
		{Width: 32, Height: 32, DataSize: 3},
	}
	got, err := pickICOEntry(entries)
	if err != nil {
		t.Fatalf("pickICOEntry: %v", err)
	}
	if got.Width != 32 {
		t.Fatalf("应选 32x32，实际 %dx%d", got.Width, got.Height)
	}

	// 只有非正方形时退化为面积最大的一档。
	only := []icoEntry{
		{Width: 16, Height: 16, DataSize: 1},
		{Width: 48, Height: 24, DataSize: 2},
	}
	got, err = pickICOEntry(only)
	if err != nil {
		t.Fatalf("pickICOEntry: %v", err)
	}
	if got.DataSize != 2 {
		t.Fatalf("应退化为面积最大项，实际 %dx%d", got.Width, got.Height)
	}

	if _, err := pickICOEntry(nil); err == nil {
		t.Fatal("空列表应报错")
	}
}
