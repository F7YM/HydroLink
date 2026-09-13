package tray

import (
	"bytes"
	"encoding/binary"
	"fmt"
)

// 托盘图标在 Windows 上必须是 HICON，而仓库里只有 PNG(appicon.png) 与
// ICO(icon.ico)。这里解析 ICO 容器，取出单个图标图像的原始 DIB/PNG 数据，
// 交给 CreateIconFromResourceEx。放在无构建标签的文件里，于是能在 Linux 上单测。

// icoEntry 是 ICO 目录里的一项。
type icoEntry struct {
	Width      int
	Height     int
	BitCount   int
	DataOffset uint32
	DataSize   uint32
	// PNG 为真表示该项内嵌 PNG（Vista+ 允许）。
	PNG bool
}

// parseICOEntries 解析 ICO 目录（不含图像数据）。
func parseICOEntries(data []byte) ([]icoEntry, error) {
	if len(data) < 6 {
		return nil, fmt.Errorf("ICO 文件过短")
	}
	if binary.LittleEndian.Uint16(data[0:2]) != 0 {
		return nil, fmt.Errorf("ICO reserved 字段非 0")
	}
	if typ := binary.LittleEndian.Uint16(data[2:4]); typ != 1 {
		return nil, fmt.Errorf("不是 ICO（type=%d）", typ)
	}
	count := int(binary.LittleEndian.Uint16(data[4:6]))
	if count == 0 {
		return nil, fmt.Errorf("ICO 中没有图像")
	}
	if len(data) < 6+count*16 {
		return nil, fmt.Errorf("ICO 目录被截断")
	}
	out := make([]icoEntry, 0, count)
	for i := 0; i < count; i++ {
		off := 6 + i*16
		e := icoEntry{
			// 0 表示 256（ICO 用单字节表示尺寸）
			Width:      int(data[off]),
			Height:     int(data[off+1]),
			BitCount:   int(binary.LittleEndian.Uint16(data[off+6 : off+8])),
			DataSize:   binary.LittleEndian.Uint32(data[off+8 : off+12]),
			DataOffset: binary.LittleEndian.Uint32(data[off+12 : off+16]),
		}
		if e.Width == 0 {
			e.Width = 256
		}
		if e.Height == 0 {
			e.Height = 256
		}
		end := uint64(e.DataOffset) + uint64(e.DataSize)
		if end > uint64(len(data)) {
			return nil, fmt.Errorf("ICO 第 %d 项数据越界", i)
		}
		if e.DataSize >= 8 && bytes.Equal(data[e.DataOffset:e.DataOffset+8], []byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}) {
			e.PNG = true
		}
		out = append(out, e)
	}
	return out, nil
}

// pickICOEntry 选择最合适的图标项：优先 >= 32px 的正方形里最小的一档
// （托盘图标 16~32px），没有就退而取最大的一档。
func pickICOEntry(entries []icoEntry) (icoEntry, error) {
	if len(entries) == 0 {
		return icoEntry{}, fmt.Errorf("ICO 中没有图像")
	}
	best := entries[0]
	found := false
	for _, e := range entries {
		if e.Width == e.Height && e.Width >= 32 {
			if !found || e.Width < best.Width {
				best, found = e, true
			}
		}
	}
	if found {
		return best, nil
	}
	for _, e := range entries {
		if e.Width*e.Height > best.Width*best.Height {
			best = e
		}
	}
	return best, nil
}

// IconFromICO 返回可直接喂给 CreateIconFromResourceEx 的图像数据与期望尺寸。
func IconFromICO(data []byte) (img []byte, width, height int, err error) {
	entries, err := parseICOEntries(data)
	if err != nil {
		return nil, 0, 0, err
	}
	e, err := pickICOEntry(entries)
	if err != nil {
		return nil, 0, 0, err
	}
	return data[e.DataOffset : e.DataOffset+e.DataSize], e.Width, e.Height, nil
}
