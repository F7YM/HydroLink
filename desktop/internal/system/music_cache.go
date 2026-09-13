package system

import (
	"fmt"
	"sync"
	"time"
)

// 音乐信息来自外部（playerctl / osascript / Windows SMTC），每次查询都要起进程
// 或跨进程调用，而手表会按 refreshMs 轮询 —— 所以统一加一层 TTL 缓存 + 单飞
// （并发请求只查一次），失败结果同样缓存，避免对着坏掉的后端反复重试。
//
// 这一层与平台无关，可在 Linux 上单测；各平台只实现 musicBackend。

// musicBackend 是平台侧的音乐查询/控制实现。
type musicBackend interface {
	// read 返回当前曲目；没有播放内容时返回 HasMedia=false 且 err 为 nil。
	read() (MusicInfo, error)
	// control 执行播放控制，返回是否成功。
	control(action string) (bool, error)
}

// cachedMusicBackend 给 musicBackend 加 TTL 缓存与单飞。
type cachedMusicBackend struct {
	backend musicBackend
	ttl     time.Duration
	now     func() time.Time

	mu       sync.Mutex
	info     MusicInfo
	err      error
	fetched  time.Time
	hasValue bool
	inflight chan struct{}
}

func newCachedMusicBackend(b musicBackend, ttl time.Duration) *cachedMusicBackend {
	return &cachedMusicBackend{backend: b, ttl: ttl, now: time.Now}
}

// Get 返回当前曲目（永不报错：查询失败等价于"没有在放"）。
func (c *cachedMusicBackend) Get() MusicInfo {
	c.mu.Lock()
	if c.hasValue && c.now().Sub(c.fetched) < c.ttl {
		info := c.info
		c.mu.Unlock()
		return info
	}
	// 已有查询在进行 → 等它，避免并发重复查询外部进程。
	if c.inflight != nil {
		ch := c.inflight
		c.mu.Unlock()
		<-ch
		c.mu.Lock()
		info := c.info
		c.mu.Unlock()
		return info
	}
	ch := make(chan struct{})
	c.inflight = ch
	c.mu.Unlock()

	info, err := c.readSafely()

	c.mu.Lock()
	c.err = err
	c.fetched = c.now()
	c.hasValue = true
	if err != nil {
		info = MusicInfo{HasMedia: false}
	}
	c.info = info
	c.inflight = nil
	close(ch)
	c.mu.Unlock()
	return info
}

// readSafely 调用后端并兜住 panic。
//
// 平台侧是跨进程/跨 COM 的调用（Windows 的 WinRT、Linux 的 playerctl），
// 一个 nil 解引用就会 panic；这里把它降级成"没有在放音乐 + 记日志"，
// 绝不让一次查询失败拖垮进程。
func (c *cachedMusicBackend) readSafely() (info MusicInfo, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("音乐查询 panic: %v", r)
			logf("system: 音乐查询 panic: %v", r)
			info = MusicInfo{HasMedia: false}
		}
	}()
	return c.backend.read()
}

// Control 执行播放控制；成功后作废缓存，让下一次 Get 立刻反映新状态。
func (c *cachedMusicBackend) Control(action string) bool {
	ok, err := c.controlSafely(action)
	if err == nil && ok {
		c.mu.Lock()
		c.fetched = time.Time{}
		c.hasValue = false
		c.mu.Unlock()
	}
	return err == nil && ok
}

// controlSafely 同样兜住平台侧 panic（例如 WinRT 调用里的 nil 解引用）。
func (c *cachedMusicBackend) controlSafely(action string) (ok bool, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("音乐控制 panic: %v", r)
			logf("system: 音乐控制 panic: %v", r)
			ok = false
		}
	}()
	return c.backend.control(action)
}

// LastError 返回最近一次查询的错误（仅用于日志/诊断）。
func (c *cachedMusicBackend) LastError() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.err
}
