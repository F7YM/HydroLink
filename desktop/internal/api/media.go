package api

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/disintegration/imaging"

	"hydrolink-desktop/internal/system"
)

// handleMusicArt serves the current track's cover art:
//
//	GET /api/music/art                 -> original
//	GET /api/music/art?blur=26&dim=0.3 -> blurred + darkened (background use)
//
// The processed variant is cached under <dataDir>/cache/music-art by
// (artURL, blur, dim) and returned as JPEG.
func (s *Server) handleMusicArt(w http.ResponseWriter, r *http.Request) {
	info := system.GetMusic()
	art, err := system.FetchAlbumArt(info)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"status": "error", "message": err.Error()})
		return
	}

	q := r.URL.Query()
	blurF, _ := strconv.ParseFloat(q.Get("blur"), 64)
	dimF, _ := strconv.ParseFloat(q.Get("dim"), 64)
	size, _ := strconv.Atoi(q.Get("size"))
	// w/h: 输出裁剪到屏幕尺寸（cover 语义），避免客户端 background-size 不生效
	// 时方图在更高屏幕上出现黑边。
	width, _ := strconv.Atoi(q.Get("w"))
	height, _ := strconv.Atoi(q.Get("h"))
	if blurF <= 0 && dimF <= 0 && size <= 0 {
		w.Header().Set("Content-Type", art.ContentType)
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(art.Bytes)
		return
	}

	cachePath := s.artCachePath(art.Source, blurF, dimF, size, width, height)
	if data, err := os.ReadFile(cachePath); err == nil {
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(data)
		return
	}

	src, _, err := image.Decode(bytes.NewReader(art.Bytes))
	if err != nil {
		writeJSON(w, http.StatusUnsupportedMediaType, map[string]any{"status": "error", "message": "无法解码封面: " + err.Error()})
		return
	}
	out := processArt(src, blurF, dimF, size, width, height)
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, out, &jpeg.Options{Quality: 86}); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"status": "error", "message": err.Error()})
		return
	}
	if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err == nil {
		_ = os.WriteFile(cachePath, buf.Bytes(), 0o644)
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(buf.Bytes())
}

// processArt crops to w x h when both > 0 (cover semantics), otherwise fits
// into size x size, then blurs (radius in output pixels) and dims (factor =
// share of brightness kept).
func processArt(src image.Image, blur, dim float64, size, w, h int) image.Image {
	out := src
	if w > 0 && h > 0 {
		out = imaging.Fill(out, w, h, imaging.Center, imaging.Lanczos)
	} else if size > 0 && (src.Bounds().Dx() > size || src.Bounds().Dy() > size) {
		out = imaging.Fit(out, size, size, imaging.Lanczos)
	}
	if blur > 0 {
		out = imaging.Blur(out, blur)
	}
	if dim > 0 && dim < 1 {
		// "压暗 30%" = keep 30% of the original brightness (single, predictable
		// scaling; AdjustBrightness would clamp and double-darken).
		out = blendBlack(out, dim)
	}
	return out
}

// blendBlack scales every pixel toward black by factor (1-dim).
func blendBlack(src image.Image, dim float64) image.Image {
	b := src.Bounds()
	dst := image.NewRGBA(b)
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			r, g, bl, a := src.At(x, y).RGBA()
			dst.Set(x, y, color.RGBA{
				R: uint8(float64(r>>8) * dim),
				G: uint8(float64(g>>8) * dim),
				B: uint8(float64(bl>>8) * dim),
				A: uint8(a >> 8),
			})
		}
	}
	return dst
}

func (s *Server) artCachePath(source string, blur, dim float64, size, w, h int) string {
	key := fmt.Sprintf("%s|%.2f|%.2f|%d|%d|%d", source, blur, dim, size, w, h)
	sum := sha1.Sum([]byte(key))
	name := hex.EncodeToString(sum[:]) + ".jpg"
	return filepath.Join(s.store.Dir(), "cache", "music-art", name)
}

// ---- kit.system (JS plugins; WASM plugins get this via the ABI context) ----

// handleSystem serves host system metrics to plugin workers:
//
//	POST /api/hydro/worker/system  {"package": id, "op": "perf"}
//
// Requires the "system" permission.
func (h *WorkerHandler) handleSystem(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Package string `json:"package"`
		Op      string `json:"op"`
	}
	if !parseWorkerBody(r, &body) {
		writeWorkerError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if !h.requirePermission(w, body.Package, "system") {
		return
	}
	switch body.Op {
	case "", "perf":
		p, err := system.GetPerf()
		if err != nil {
			writeWorkerError(w, http.StatusInternalServerError, "读取性能数据失败: "+err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"cpu":        p.CPU,
			"memPercent": p.MemPercent,
			"memUsedMB":  p.MemUsedMB,
			"memTotalMB": p.MemTotalMB,
		})
	case "music":
		info := system.GetMusic()
		writeJSON(w, http.StatusOK, map[string]any{
			"hasMedia": info.HasMedia,
			"title":    info.Title,
			"artist":   info.Artist,
			"playing":  info.Playing,
			"artUrl":   strings.TrimSuffix(strings.TrimPrefix(info.ArtURL, "file://"), "\n"),
		})
	default:
		writeWorkerError(w, http.StatusBadRequest, "未知操作: "+body.Op)
	}
}
