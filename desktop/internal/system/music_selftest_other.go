//go:build !windows

package system

// MusicSelfTest 在非 Windows 平台直接查一次（这里没有 TTL 缓存的差异）。
func MusicSelfTest() (MusicInfo, string) { return GetMusic(), "" }

// CoverArtSelfTest：非 Windows 平台没有独立的封面读取阶段。
func CoverArtSelfTest() (string, string) {
	info := GetMusic()
	if info.ArtURL == "" {
		return "", "当前曲目没有封面引用（artUrl 为空）"
	}
	art, err := FetchAlbumArt(info)
	if err != nil {
		return "", err.Error()
	}
	return "已获取 " + itoaBytes(len(art.Bytes)) + " 字节", ""
}

func itoaBytes(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
