// Package system: art.go resolves album art bytes from a raw MPRIS art URL.
package system

import (
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// AlbumArt is resolved cover art.
type AlbumArt struct {
	Bytes       []byte
	ContentType string
	Source      string
}

// FetchAlbumArt downloads/reads the cover art referenced by info.ArtURL.
// file:// (and plain paths) are read from disk; http(s):// are fetched with a
// short timeout. Returns an error when there is no usable art.
func FetchAlbumArt(info MusicInfo) (*AlbumArt, error) {
	raw := strings.TrimSpace(info.ArtURL)
	if raw == "" {
		return nil, fmt.Errorf("当前曲目没有封面")
	}
	if strings.HasPrefix(raw, "http://") || strings.HasPrefix(raw, "https://") {
		client := &http.Client{Timeout: 10 * time.Second}
		resp, err := client.Get(raw)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("封面下载失败: HTTP %d", resp.StatusCode)
		}
		data, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
		if err != nil {
			return nil, err
		}
		ct := resp.Header.Get("Content-Type")
		if ct == "" {
			ct = "image/jpeg"
		}
		return &AlbumArt{Bytes: data, ContentType: ct, Source: raw}, nil
	}
	path := raw
	if strings.HasPrefix(raw, "file://") {
		if u, err := url.Parse(raw); err == nil {
			// Windows 上 url.Path 是 "/C:/dir/x.jpg"，直接 ReadFile 会失败。
			path = filePathFromURL(u)
		}
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	// 按文件头判断类型：Windows 的 SMTC 缩略图多为 PNG，硬写 image/jpeg 会让
	// 下游（浏览器/手表）按错的 MIME 处理。
	return &AlbumArt{Bytes: data, ContentType: mimeForImage(data), Source: raw}, nil
}
