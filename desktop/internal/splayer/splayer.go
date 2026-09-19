// Package splayer 对接 SPlayer 桌面播放器的本地 WebSocket API
// （默认 ws://127.0.0.1:25885，见 https://splayer.imsyy.top/socket.html），
// 把它的播放进度与歌词事件收敛成"当前这一句歌词"，供插件渲染。
//
// 设计要点：
//
//   - 连接是**按需**的：调用方（插件 WebUI 的状态轮询、或插件视图的上下文
//     构建）每次取值前 Touch 一次，超过 idleTimeout 没人要就断开——宿主不会
//     在用户没开歌词功能时白占一个连接。
//   - SPlayer 的事件里只有"播放进度"是高频的（约 500ms），歌词在切歌/
//     加载完成时才推一次；因此歌词只在内存里留一份拍平后的行数组，
//     每帧用进度二分定位，不做任何网络回查。
package splayer

import (
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// DefaultPort 是 SPlayer WebSocket 服务的默认端口（用户可在其设置里改）。
const DefaultPort = 25885

const (
	// idleTimeout：这么久没有 Touch 就断开（插件视图按 refreshMs 轮询，
	// 关闭页面后连接会自然回收）。
	idleTimeout = 20 * time.Second
	dialTimeout = 3 * time.Second
	tickPeriod  = 2 * time.Second
	pingPeriod  = 20 * time.Second
)

// Line 是一句歌词（时间轴已统一为毫秒）。
type Line struct {
	StartMs int64
	EndMs   int64
	Text    string
}

// Snapshot 是插件/WebUI 能看到的实时状态。
type Snapshot struct {
	Connected  bool   `json:"connected"`
	Playing    bool   `json:"playing"`
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	PositionMs int64  `json:"positionMs"`
	// Lyric 是当前这一句，Next 是下一句（都去掉空行，可能为空串）。
	Lyric string `json:"line"`
	Next  string `json:"next"`
	// HasTrack 表示 SPlayer 至少报过一次曲目信息。
	HasTrack bool `json:"hasTrack"`
}

// Client 是一条懒连接：没人用就断开。
type Client struct {
	logf func(format string, args ...any)

	mu        sync.Mutex
	conn      *websocket.Conn
	port      int
	lastTouch time.Time
	running   bool

	lines     []Line
	posMs     int64
	posAt     time.Time
	playing   bool
	title     string
	artist    string
	hasTrack  bool
	lastPing  time.Time
	lastError string
}

// New 创建一个未连接的客户端。logf 可为 nil。
func New(logf func(format string, args ...any)) *Client {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	return &Client{logf: logf, port: DefaultPort}
}

// Touch 声明"还有人要歌词"，并（在需要时）建立连接或切换到新端口。
func (c *Client) Touch(port int) {
	if port <= 0 || port > 65535 {
		port = DefaultPort
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if port != c.port {
		c.port = port
		c.closeLocked()
	}
	c.lastTouch = time.Now()
	if !c.running {
		c.running = true
		go c.loop()
	}
}

// Snapshot 返回当前状态；返回值永远可用于渲染（未连接时 Connected=false）。
func (c *Client) Snapshot() Snapshot {
	c.mu.Lock()
	defer c.mu.Unlock()
	pos := c.pos()
	cur, next := lyricAt(c.lines, pos)
	return Snapshot{
		Connected:  c.conn != nil,
		Playing:    c.playing,
		Title:      c.title,
		Artist:     c.artist,
		PositionMs: pos,
		Lyric:      cur,
		Next:       next,
		HasTrack:   c.hasTrack,
	}
}

// Close 断开连接并停止后台循环（应用退出时调用）。
func (c *Client) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.running = false
	c.closeLocked()
}

// pos 返回插值后的播放位置（进展事件之间按墙钟时间推进），调用方需持锁。
func (c *Client) pos() int64 {
	if !c.playing || c.posAt.IsZero() {
		return c.posMs
	}
	d := time.Since(c.posAt)
	if d < 0 || d > 5*time.Second {
		return c.posMs
	}
	return c.posMs + d.Milliseconds()
}

func (c *Client) closeLocked() {
	if c.conn != nil {
		_ = c.conn.Close()
		c.conn = nil
	}
	// 断开后歌词/进度失效，避免下次连接前显示旧内容
	c.lines = nil
	c.posMs = 0
	c.posAt = time.Time{}
}

// ---- 后台循环：连接 / 心跳 / 空闲回收 ----

func (c *Client) loop() {
	for {
		if !c.tick() {
			return
		}
		time.Sleep(tickPeriod)
	}
}

// tick 做一轮维护：空闲回收、建连、心跳；返回 false 表示循环该结束了。
func (c *Client) tick() bool {
	c.mu.Lock()
	if !c.running || time.Since(c.lastTouch) > idleTimeout {
		c.running = false
		c.closeLocked()
		c.mu.Unlock()
		return false
	}
	conn, port := c.conn, c.port
	needPing := conn != nil && time.Since(c.lastPing) > pingPeriod
	if needPing {
		c.lastPing = time.Now()
	}
	c.mu.Unlock()

	switch {
	case conn == nil:
		c.connect(port)
	case needPing:
		_ = conn.WriteMessage(websocket.TextMessage, []byte("PING"))
	}
	return true
}

func (c *Client) connect(port int) {
	dialer := websocket.Dialer{HandshakeTimeout: dialTimeout}
	u := url.URL{Scheme: "ws", Host: fmt.Sprintf("127.0.0.1:%d", port)}
	conn, _, err := dialer.Dial(u.String(), nil)
	if err != nil {
		c.setError(fmt.Sprintf("连接 SPlayer 失败（%s）：%v", u.String(), err))
		return
	}
	c.mu.Lock()
	c.conn = conn
	c.lastPing = time.Now()
	c.lastError = ""
	c.mu.Unlock()
	c.logf("splayer: 已连接 %s", u.String())

	// 连上先要一次完整曲目+歌词
	_ = conn.WriteMessage(websocket.TextMessage, []byte(`{"type":"get-song-info"}`))
	go c.readLoop(conn)
}

func (c *Client) readLoop(conn *websocket.Conn) {
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			c.mu.Lock()
			if c.conn == conn {
				c.closeLocked()
			}
			c.mu.Unlock()
			return
		}
		c.handle(data)
	}
}

func (c *Client) setError(msg string) {
	c.mu.Lock()
	changed := c.lastError != msg
	c.lastError = msg
	c.mu.Unlock()
	if changed {
		c.logf("splayer: %s", msg)
	}
}

// ---- 消息处理 ----

// wsMsg 是 SPlayer 统一的消息信封 {type, data}。
type wsMsg struct {
	Type string          `json:"type"`
	Data json.RawMessage `json:"data"`
}

// handle 处理一条服务端消息（PONG 是裸字符串，直接忽略）。
func (c *Client) handle(raw []byte) {
	if strings.TrimSpace(string(raw)) == "PONG" {
		return
	}
	var msg wsMsg
	if err := json.Unmarshal(raw, &msg); err != nil {
		return
	}
	var data map[string]any
	if len(msg.Data) > 0 {
		_ = json.Unmarshal(msg.Data, &data)
	}
	switch msg.Type {
	case "song-info":
		c.applySongInfo(data)
	case "lyric-change":
		c.applyLyric(data)
	case "song-change":
		c.applySongChange(data)
	case "progress-change":
		c.applyProgress(data)
	case "status-change":
		if b, ok := data["status"].(bool); ok {
			c.mu.Lock()
			c.playing = b
			c.posAt = time.Now()
			c.mu.Unlock()
		}
	case "error":
		if m, ok := data["message"].(string); ok {
			c.setError(m)
		}
	}
}

func (c *Client) applySongInfo(data map[string]any) {
	if data == nil {
		return
	}
	title := firstString(data, "name", "title", "playName")
	artist := firstString(data, "artist", "artists", "artistName")
	duration := firstNumber(data, "duration")
	posMs := timeToMs(firstNumber(data, "currentTime"), duration)
	lines := parseLines(data["lrcData"])

	c.mu.Lock()
	if title != "" {
		c.title = title
		c.hasTrack = true
	}
	if artist != "" {
		c.artist = artist
	}
	if lines != nil {
		c.lines = lines
	}
	c.posMs = posMs
	c.posAt = time.Now()
	switch v := data["playStatus"].(type) {
	case string:
		c.playing = v == "play" || v == "playing"
	case bool:
		c.playing = v
	}
	c.mu.Unlock()
}

func (c *Client) applyLyric(data map[string]any) {
	if data == nil {
		return
	}
	lines := parseLines(data["lrcData"])
	if lines == nil {
		// 逐字歌词同样能拼出行文本，普通歌词缺失时退回它
		lines = parseLines(data["yrcData"])
	}
	if lines == nil {
		return
	}
	c.mu.Lock()
	c.lines = lines
	c.mu.Unlock()
}

func (c *Client) applySongChange(data map[string]any) {
	if data == nil {
		return
	}
	c.mu.Lock()
	if t := firstString(data, "name", "title"); t != "" {
		c.title = t
		c.hasTrack = true
	}
	if a := firstString(data, "artist", "artists"); a != "" {
		c.artist = a
	}
	// 切歌后旧歌词立即失效，等 lyric-change / 下次 get-song-info
	c.lines = nil
	c.posMs = 0
	c.posAt = time.Now()
	c.mu.Unlock()
}

func (c *Client) applyProgress(data map[string]any) {
	if data == nil {
		return
	}
	duration := firstNumber(data, "duration")
	posMs := timeToMs(firstNumber(data, "currentTime"), duration)
	c.mu.Lock()
	c.posMs = posMs
	c.posAt = time.Now()
	c.mu.Unlock()
}

// ---- 纯函数：时间与歌词 ----

// timeToMs 把 SPlayer 的两种时间口径统一成毫秒：进度事件是毫秒
// （文档明确），而 song-info 用的是秒（浮点）。用 duration 判口径——
// 一首歌的"秒"不会超过 10 小时，所以 duration > 36000 必然是毫秒。
func timeToMs(v float64, duration float64) int64 {
	if v <= 0 {
		return 0
	}
	if duration > 36000 {
		return int64(v)
	}
	if duration > 0 {
		return int64(v * 1000)
	}
	// 没有 duration 可参照时：带小数的一律当秒，整数大到不像秒的当毫秒
	if v != float64(int64(v)) || v < 2000 {
		return int64(v * 1000)
	}
	return int64(v)
}

var lrcTagRe = regexp.MustCompile(`^\[(\d+):(\d+(?:[.:]\d+)?)\]`)

// parseLines 把 SPlayer 的歌词数组拍平成时间轴。它接受三种形态：
//
//   - AMLL 的 LyricLine：{"words":[{"word":"..."}],"startTime":ms,"endTime":ms}
//     （SPlayer 内部用的就是它，也是唯一有保证的形态）
//   - {"time":…,"text":…} 这类朴素结构
//   - 原始 LRC 行（"[00:12.34]歌词"）
//
// 全部无法识别时返回 nil，调用方保持原有歌词不变。
func parseLines(raw any) []Line {
	arr, ok := raw.([]any)
	if !ok || len(arr) == 0 {
		return nil
	}
	unitHint := unitUnknown
	if _, isStr := arr[0].(string); isStr {
		unitHint = unitMs
	}
	out := make([]Line, 0, len(arr))
	for _, item := range arr {
		switch v := item.(type) {
		case string:
			if l, ok := parseLrcLine(v); ok {
				out = append(out, l)
			}
		case map[string]any:
			if l, ok := parseLineObject(v, unitHint); ok {
				out = append(out, l)
			}
		}
	}
	if len(out) == 0 {
		return nil
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].StartMs < out[j].StartMs })
	return out
}

type unitKind int

const (
	unitUnknown unitKind = iota
	unitMs
	unitSec
)

func parseLineObject(m map[string]any, hint unitKind) (Line, bool) {
	text := lineText(m)
	start, ok := numberAt(m, "startTime")
	unit := hint
	if !ok {
		start, ok = numberAt(m, "time")
		if !ok {
			return Line{}, false
		}
		if unit == unitUnknown {
			// 朴素结构里 "time" 更常见的是秒（LRC 风格）
			unit = unitSec
			if start > 3600 {
				unit = unitMs
			}
		}
	}
	if unit == unitSec {
		start *= 1000
	}
	end, ok := numberAt(m, "endTime")
	if ok && unit == unitSec {
		end *= 1000
	}
	return Line{StartMs: int64(start), EndMs: int64(end), Text: strings.TrimSpace(text)}, true
}

// lineText 取出这一行的文本：优先拼 AMLL 的逐字数组。
func lineText(m map[string]any) string {
	if words, ok := m["words"].([]any); ok {
		var b strings.Builder
		for _, w := range words {
			if wm, ok := w.(map[string]any); ok {
				if s, ok := wm["word"].(string); ok {
					b.WriteString(s)
				}
			}
		}
		if b.Len() > 0 {
			return b.String()
		}
	}
	return firstString(m, "text", "lineLyric", "lrc", "content", "lyric")
}

func parseLrcLine(s string) (Line, bool) {
	m := lrcTagRe.FindStringSubmatch(strings.TrimSpace(s))
	if m == nil {
		return Line{}, false
	}
	min, _ := strconv.Atoi(m[1])
	rest := strings.Replace(m[2], ":", ".", 1)
	sec, err := strconv.ParseFloat(rest, 64)
	if err != nil {
		return Line{}, false
	}
	text := strings.TrimSpace(s[len(m[0]):])
	return Line{StartMs: int64(min)*60000 + int64(sec*1000), Text: text}, true
}

// lyricAt 返回 posMs 处的歌词行与紧随其后的一句（都跳过空行）。
func lyricAt(lines []Line, posMs int64) (cur, next string) {
	if len(lines) == 0 {
		return "", ""
	}
	// 最后一个 start <= pos 的行
	i := sort.Search(len(lines), func(i int) bool { return lines[i].StartMs > posMs }) - 1
	if i < 0 {
		return "", firstNonEmpty(lines, 0)
	}
	cur = lineTextAt(lines, i)
	if cur == "" {
		// 间奏（空行）：显示上一句，避免歌词区闪空
		for j := i - 1; j >= 0; j-- {
			if t := lines[j].Text; t != "" {
				cur = t
				break
			}
		}
	}
	next = firstNonEmpty(lines, i+1)
	return cur, next
}

func lineTextAt(lines []Line, i int) string {
	if i < 0 || i >= len(lines) {
		return ""
	}
	return lines[i].Text
}

func firstNonEmpty(lines []Line, from int) string {
	for i := from; i < len(lines); i++ {
		if lines[i].Text != "" {
			return lines[i].Text
		}
	}
	return ""
}

func firstString(m map[string]any, keys ...string) string {
	for _, k := range keys {
		switch v := m[k].(type) {
		case string:
			if v != "" {
				return v
			}
		case []any: // artists 有时是数组
			if len(v) > 0 {
				if s, ok := v[0].(string); ok && s != "" {
					return s
				}
			}
		}
	}
	return ""
}

func firstNumber(m map[string]any, keys ...string) float64 {
	for _, k := range keys {
		if v, ok := numberAt(m, k); ok {
			return v
		}
	}
	return 0
}

func numberAt(m map[string]any, key string) (float64, bool) {
	switch v := m[key].(type) {
	case float64:
		return v, true
	case json.Number:
		f, err := v.Float64()
		return f, err == nil
	case string:
		f, err := strconv.ParseFloat(v, 64)
		return f, err == nil
	}
	return 0, false
}
