//go:build !windows

package system

import "fmt"

// MusicSelfTest 在非 Windows 平台直接查一次（这里没有 TTL 缓存的差异）。
func MusicSelfTest() (MusicInfo, error) { return GetMusic(), nil }

// CoverArtSelfTest：非 Windows 平台没有独立的封面读取阶段。
func CoverArtSelfTest() (string, error) {
	info := GetMusic()
	if info.ArtURL == "" {
		return "", fmt.Errorf("当前曲目没有封面引用（artUrl 为空）")
	}
	art, err := FetchAlbumArt(info)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("已获取 %d 字节", len(art.Bytes)), nil
}
