package api

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/store"
)

// synthImage builds a recognizable test image (diagonal gradient + blocks).
func synthImage(w, h int) image.Image {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{
				R: uint8(x * 255 / w),
				G: uint8(y * 255 / h),
				B: 200,
				A: 255,
			})
		}
	}
	for y := h / 4; y < h/2; y++ {
		for x := w / 4; x < w/2; x++ {
			img.Set(x, y, color.RGBA{R: 255, G: 255, B: 255, A: 255})
		}
	}
	return img
}

func TestProcessArtBlurAndDim(t *testing.T) {
	src := synthImage(400, 400)

	// dim only: brightness scales down, size preserved
	dimmed := processArt(src, 0, 0.3, 0, 0, 0)
	if dimmed.Bounds().Dx() != 400 || dimmed.Bounds().Dy() != 400 {
		t.Fatalf("dim changed bounds: %v", dimmed.Bounds())
	}
	r0, g0, _, _ := src.At(200, 200).RGBA()
	rd, gd, _, _ := dimmed.At(200, 200).RGBA()
	if rd >= r0 || gd >= g0 {
		t.Fatalf("dim did not darken (200,200): src=(%d,%d) dst=(%d,%d)", r0, g0, rd, gd)
	}
	// dim 0.3 keeps ~30% of the original level
	if rd*100/r0 > 45 || rd*100/r0 < 20 {
		t.Fatalf("dim factor off: src=%d dst=%d (~%d%%)", r0, rd, rd*100/r0)
	}

	// resize + blur + dim: output is square-sized and edges soften
	blurred := processArt(src, 26, 0.3, 200, 0, 0)
	if blurred.Bounds().Dx() != 200 || blurred.Bounds().Dy() != 200 {
		t.Fatalf("size not applied: %v", blurred.Bounds())
	}
	// sample across the white block's left edge: blurred version is smoother
	edgeSharp := edgeDelta(src, 100, 200, 90, 110)
	edgeSoft := edgeDelta(blurred, 100, 200, 90, 110)
	if edgeSoft >= edgeSharp {
		t.Fatalf("blur did not soften edge: sharp=%d soft=%d", edgeSharp, edgeSoft)
	}

	// write a JPEG for manual inspection (blur 26 + dim 30%)
	f, err := os.Create(filepath.Join(t.TempDir(), "art-blur26-dim30.jpg"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := jpeg.Encode(f, blurred, &jpeg.Options{Quality: 86}); err != nil {
		t.Fatal(err)
	}
}

// edgeDelta measures the grey-level jump across a horizontal edge.
func edgeDelta(img image.Image, y, x0, x1, x2 int) uint32 {
	a, _, _, _ := img.At(x1, y).RGBA()
	b, _, _, _ := img.At(x2, y).RGBA()
	if a > b {
		return (a - b) >> 8
	}
	return (b - a) >> 8
}

func TestProcessArtCropsToScreenSize(t *testing.T) {
	// w/h（cover 语义）：方图裁成屏幕比例，避免客户端出现黑边
	out := processArt(synthImage(400, 400), 26, 0.3, 0, 432, 514)
	if out.Bounds().Dx() != 432 || out.Bounds().Dy() != 514 {
		t.Fatalf("crop size = %v, want 432x514", out.Bounds())
	}
}

// TestProcessArtParamsShortcut pins the w/h + size/blur/dim combination that
// plugins actually request (deskbuddy: ?w=466&h=466&size=466&blur=26&dim=0.3).
//
// NOTE: the "no params -> original" shortcut lives in handleMusicArt (it checks
// only blur/dim/size, so w/h alone returns the source), and exercising it needs
// a live media session, so it is pinned on the Android side instead:
// MusicArtRouteTest.onlyWhParamsFallBackToOriginalLikeDesktop. Keep the two
// conditions in sync — same URL must yield the same image size on both ends.
func TestProcessArtParamsShortcut(t *testing.T) {
	if out := processArt(synthImage(363, 363), 26, 0.3, 466, 800, 300); out.Bounds().Dx() != 800 || out.Bounds().Dy() != 300 {
		t.Fatalf("w/h with size+blur+dim = %v, want 800x300", out.Bounds())
	}
}

// TestProcessArtFitGeometry pins the exact output geometry of the
// size=...&blur=...&dim=... recipe that plugins get as `music.artBlur`
// (466x466 watch background). The Android port
// (MusicArtProcessorTest.watchBackgroundRecipeMatchesDesktopQuery) asserts the
// same numbers, so a change on one side that the other does not follow fails
// here first: imaging.Fit truncates (349.5 -> 349), it does not round.
func TestProcessArtFitGeometry(t *testing.T) {
	out := processArt(synthImage(1200, 900), 26, 0.3, 466, 0, 0)
	if out.Bounds().Dx() != 466 || out.Bounds().Dy() != 349 {
		t.Fatalf("fit size = %v, want 466x349", out.Bounds())
	}
	// 只缩不放：比 size 小的图保持原样
	small := processArt(synthImage(100, 80), 0, 0, 466, 0, 0)
	if small.Bounds().Dx() != 100 || small.Bounds().Dy() != 80 {
		t.Fatalf("small image upscaled: %v", small.Bounds())
	}
}

func TestMusicArtEndpointWithoutMedia(t *testing.T) {
	st := store.NewIn(t.TempDir())
	srv := New(st)
	rr := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/music/art", nil))
	// Depends on the host media session: 200 + an image when a track with a
	// cover is playing, 404 with a JSON message otherwise. Never 500.
	switch rr.Code {
	case http.StatusOK:
		ct := rr.Header().Get("Content-Type")
		if !strings.HasPrefix(ct, "image/") || rr.Body.Len() == 0 {
			t.Fatalf("art 200 but ct=%q len=%d", ct, rr.Body.Len())
		}
	case http.StatusNotFound:
		if !strings.Contains(rr.Body.String(), "error") {
			t.Fatalf("art 404 without message: %s", rr.Body.String())
		}
	default:
		t.Fatalf("art -> %d: %s", rr.Code, rr.Body.String())
	}
}

func TestWorkerSystemPermission(t *testing.T) {
	st := workerTestStore(t) // com.t.worker has permissions storage+notify, not system
	h := NewWorkerHandler(hydrokit.NewWorkerBridge(), st, nil)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/hydro/worker/system",
		bytes.NewBufferString(`{"package":"com.t.worker","op":"perf"}`)))
	if rr.Code != http.StatusForbidden {
		t.Fatalf("system without permission -> %d, want 403", rr.Code)
	}
}

func TestArtCachePathStable(t *testing.T) {
	st := store.NewIn(t.TempDir())
	srv := New(st)
	a := srv.artCachePath("file:///x/cover.jpg", 26, 0.3, 466, 0, 0)
	b := srv.artCachePath("file:///x/cover.jpg", 26, 0.3, 466, 0, 0)
	c := srv.artCachePath("file:///x/cover.jpg", 20, 0.3, 466, 0, 0)
	if a != b {
		t.Fatalf("cache path not stable: %s vs %s", a, b)
	}
	if a == c {
		t.Fatalf("cache path ignores params")
	}
	if filepath.Ext(a) != ".jpg" {
		t.Fatalf("unexpected cache ext: %s", a)
	}
}

// keep png import used by other tests in this package
var _ = png.Encode
