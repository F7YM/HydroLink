package system

// 与平台无关的小工具：封面 URL/临时文件/字节嗅探，以及 file:// URL → 本地路径。
// 放在无构建标签的文件里，于是 Windows 上的路径换算也能在 Linux 单测里覆盖。

import (
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// coverArtDir 返回存放封面临时文件的目录。
func coverArtDir() (string, error) {
	dir := filepath.Join(os.TempDir(), "hydrolink-art")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	return dir, nil
}

// coverArtPath 用内容指纹命名封面文件（同一曲目复用同一文件，避免堆积）。
func coverArtPath(data []byte) (string, error) {
	dir, err := coverArtDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, fmt.Sprintf("art-%08x%s", fnv32(data), guessImageExt(data))), nil
}

// fileURL 把本地路径转成 file:// URL（Windows 下 /C:/dir/file 形式）。
func fileURL(path string) string {
	p := strings.ReplaceAll(path, "\\", "/")
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	return "file://" + p
}

// filePathFromURL 把 file:// URL 换成当前平台的本地路径。
// （Windows 下 url.Parse("file:///C:/a/b.jpg").Path 是 "/C:/a/b.jpg"，
// 直接当路径用会失败 —— 这正是 cover art 在 Windows 上读不到的原因。）
func filePathFromURL(u *url.URL) string {
	return filePathFromURLFor(runtime.GOOS, u)
}

// filePathFromURLFor 是可测版本：显式指定 goos（windows/darwin/linux）。
func filePathFromURLFor(goos string, u *url.URL) string {
	if u == nil {
		return ""
	}
	host, p := u.Host, u.Path
	if host != "" && host != "localhost" {
		// UNC 路径：file://server/share/x → \\server\share\x
		unc := "//" + host + p
		if goos == "windows" {
			return windowsPath(unc)
		}
		return unc
	}
	if goos == "windows" && len(p) >= 3 && p[0] == '/' && p[2] == ':' {
		p = p[1:] // /C:/dir/x → C:/dir/x
	}
	if goos == "windows" {
		return windowsPath(p)
	}
	return p
}

// windowsPath 显式做斜杠换算（不用 filepath.FromSlash：那个依赖宿主 GOOS，
// 会让这段逻辑在非 Windows 上无法被单测覆盖）。
func windowsPath(p string) string {
	return strings.ReplaceAll(p, "/", "\\")
}

// guessImageExt 依据文件头猜扩展名（封面数据可能是 PNG/JPEG/WebP）。
func guessImageExt(data []byte) string {
	switch {
	case len(data) > 8 && string(data[1:4]) == "PNG":
		return ".png"
	case len(data) > 3 && data[0] == 0xFF && data[1] == 0xD8:
		return ".jpg"
	case len(data) > 12 && string(data[8:12]) == "WEBP":
		return ".webp"
	}
	return ".img"
}

// fnv32 是内容指纹（用于封面文件名）。
func fnv32(data []byte) uint32 {
	h := uint32(2166136261)
	for _, b := range data {
		h ^= uint32(b)
		h *= 16777619
	}
	return h
}
