package system

import (
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeMusic struct {
	reads    int32
	controls int32
	info     MusicInfo
	err      error
	ctlOK    bool
	block    chan struct{}
}

func (f *fakeMusic) read() (MusicInfo, error) {
	atomic.AddInt32(&f.reads, 1)
	if f.block != nil {
		<-f.block
	}
	return f.info, f.err
}

func (f *fakeMusic) control(action string) (bool, error) {
	atomic.AddInt32(&f.controls, 1)
	return f.ctlOK, nil
}

func TestCacheServesWithinTTL(t *testing.T) {
	f := &fakeMusic{info: MusicInfo{HasMedia: true, Title: "a"}}
	c := newCachedMusicBackend(f, 2*time.Second)
	now := time.Now()
	c.now = func() time.Time { return now }

	for i := 0; i < 5; i++ {
		if got := c.Get(); got.Title != "a" {
			t.Fatalf("第 %d 次读取 = %+v", i, got)
		}
	}
	if n := atomic.LoadInt32(&f.reads); n != 1 {
		t.Fatalf("TTL 内应当只查一次，实际 %d 次", n)
	}

	now = now.Add(3 * time.Second)
	c.Get()
	if n := atomic.LoadInt32(&f.reads); n != 2 {
		t.Fatalf("TTL 过期后应重新查询，实际 %d 次", n)
	}
}

// 失败结果也要缓存：后端坏掉时不能每个请求都去起进程。
func TestCacheCachesFailure(t *testing.T) {
	f := &fakeMusic{err: errors.New("boom")}
	c := newCachedMusicBackend(f, time.Second)
	now := time.Now()
	c.now = func() time.Time { return now }

	for i := 0; i < 4; i++ {
		if got := c.Get(); got.HasMedia {
			t.Fatalf("失败时应返回 hasMedia=false，实际 %+v", got)
		}
	}
	if n := atomic.LoadInt32(&f.reads); n != 1 {
		t.Fatalf("失败也应缓存，实际查询 %d 次", n)
	}
	if c.LastError() == nil {
		t.Fatal("LastError 应保留错误供诊断")
	}
}

// 并发请求只触发一次后端查询（单飞）。
func TestCacheSingleFlight(t *testing.T) {
	f := &fakeMusic{info: MusicInfo{HasMedia: true, Title: "x"}, block: make(chan struct{})}
	c := newCachedMusicBackend(f, time.Minute)

	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); _ = c.Get() }()
	}
	time.Sleep(50 * time.Millisecond) // 让所有 goroutine 都进入等待
	close(f.block)
	wg.Wait()

	if n := atomic.LoadInt32(&f.reads); n != 1 {
		t.Fatalf("并发读取应只查一次，实际 %d 次", n)
	}
}

func TestControlInvalidatesCache(t *testing.T) {
	f := &fakeMusic{info: MusicInfo{HasMedia: true, Playing: false}, ctlOK: true}
	c := newCachedMusicBackend(f, time.Minute)

	_ = c.Get()
	if n := atomic.LoadInt32(&f.reads); n != 1 {
		t.Fatalf("reads = %d", n)
	}
	if !c.Control("playpause") {
		t.Fatal("控制应当成功")
	}
	_ = c.Get() // 控制后必须重新查询
	if n := atomic.LoadInt32(&f.reads); n != 2 {
		t.Fatalf("控制成功后应作废缓存，实际查询 %d 次", n)
	}

	f.ctlOK = false
	if c.Control("playpause") {
		t.Fatal("控制失败应返回 false")
	}
}
