package system

import (
	"net/url"
	"path/filepath"
	"testing"
)

// Windows 上 url.Parse("file:///C:/x/a.jpg").Path 是 "/C:/x/a.jpg"，
// 直接拿去 ReadFile 一定失败 —— 这是封存在 Windows 上读不到的根因。
func TestFilePathFromURL(t *testing.T) {
	// 期望值写成字面量：Windows 分支在 Linux 上跑时 filepath 仍是 Linux 语义，
	// 所以这里断言字符串本身（用反引号表示真实的反斜杠路径）。
	cases := []struct {
		goos string
		raw  string
		want string
	}{
		{"windows", "file:///C:/Users/float/AppData/Local/Temp/hydrolink-art/art-1.jpg", `C:\Users\float\AppData\Local\Temp\hydrolink-art\art-1.jpg`},
		{"windows", "file:///D:/music/cover.png", `D:\music\cover.png`},
		{"windows", "file://server/share/art.jpg", `\\server\share\art.jpg`},
		{"linux", "file:///home/float/music/cover.jpg", "/home/float/music/cover.jpg"},
		{"darwin", "file:///Users/x/art.jpg", "/Users/x/art.jpg"},
		{"linux", "file://localhost/tmp/a.jpg", "/tmp/a.jpg"},
	}
	for _, tc := range cases {
		u, err := url.Parse(tc.raw)
		if err != nil {
			t.Fatalf("parse %s: %v", tc.raw, err)
		}
		got := filePathFromURLFor(tc.goos, u)
		if got != tc.want {
			t.Errorf("%s: %s → %q，期望 %q", tc.goos, tc.raw, got, tc.want)
		}
	}
	if got := filePathFromURLFor("linux", nil); got != "" {
		t.Errorf("nil URL 应返回空，实际 %q", got)
	}
}

func TestGuessImageExt(t *testing.T) {
	png := []byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00}
	jpg := []byte{0xFF, 0xD8, 0xFF, 0xE0}
	webp := append([]byte("RIFF1234"), []byte("WEBPVP8 ")...)
	cases := []struct {
		want string
		data []byte
	}{
		{".png", png}, {".jpg", jpg}, {".webp", webp}, {".img", []byte{1, 2, 3}},
	}
	for _, tc := range cases {
		if got := guessImageExt(tc.data); got != tc.want {
			t.Errorf("→ %s，期望 %s", got, tc.want)
		}
	}
}

func TestCoverArtPathIsContentAddressed(t *testing.T) {
	data := []byte{0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3}
	p1, err := coverArtPath(data)
	if err != nil {
		t.Fatalf("coverArtPath: %v", err)
	}
	p2, _ := coverArtPath(data)
	if p1 != p2 {
		t.Errorf("同一内容应得到同一路径: %s vs %s", p1, p2)
	}
	if filepath.Ext(p1) != ".jpg" {
		t.Errorf("扩展名应为 .jpg: %s", p1)
	}
	other, _ := coverArtPath([]byte{0xFF, 0xD8, 0xFF, 0xE0, 9, 9})
	if other == p1 {
		t.Error("不同内容不应共用文件")
	}
}
