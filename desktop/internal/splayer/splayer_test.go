package splayer

import (
	"encoding/json"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// AMLL 的 LyricLine（SPlayer 内部就是这个结构）：逐字数组 + 毫秒时间轴。
const songInfoJSON = `{
  "type": "song-info",
  "data": {
    "playStatus": "play",
    "name": "Feel Something",
    "artists": "Jaymes Young",
    "currentTime": 12.5,
    "duration": 300,
    "lrcData": [
      {"startTime": 0, "endTime": 5000, "words": [{"word": "Feel Something"}, {"word": " - Jaymes Young"}]},
      {"startTime": 5000, "endTime": 10000, "words": []},
      {"startTime": 10000, "endTime": 15000, "words": [{"word": "Make me feel something, something"}]},
      {"startTime": 15000, "endTime": 20000, "words": [{"word": "下一句"}]}
    ]
  }
}`

func TestTimeToMs(t *testing.T) {
	// song-info 的时长是秒 -> currentTime 也是秒
	if got := timeToMs(123.45, 300); got != 123450 {
		t.Fatalf("seconds: %d", got)
	}
	// progress-change 的时长是毫秒 -> currentTime 也是毫秒
	if got := timeToMs(12000, 240000); got != 12000 {
		t.Fatalf("milliseconds: %d", got)
	}
}

func TestParseLinesAcceptsAmllAndLrc(t *testing.T) {
	var data map[string]any
	if err := json.Unmarshal([]byte(songInfoJSON), &data); err != nil {
		t.Fatal(err)
	}
	lines := parseLines(data["data"].(map[string]any)["lrcData"])
	if len(lines) != 4 {
		t.Fatalf("lines: %+v", lines)
	}
	if lines[0].Text != "Feel Something - Jaymes Young" || lines[0].StartMs != 0 {
		t.Fatalf("line 0: %+v", lines[0])
	}
	if lines[2].StartMs != 10000 {
		t.Fatalf("line 2 start: %+v", lines[2])
	}

	lrc := parseLines([]any{"[00:12.34]第一句", "[00:20.00]第二句"})
	if len(lrc) != 2 || lrc[0].StartMs != 12340 || lrc[1].Text != "第二句" {
		t.Fatalf("lrc: %+v", lrc)
	}

	plain := parseLines([]any{map[string]any{"time": 3.5, "text": "朴素结构"}})
	if len(plain) != 1 || plain[0].StartMs != 3500 {
		t.Fatalf("plain: %+v", plain)
	}
	if parseLines(nil) != nil || parseLines([]any{42}) != nil {
		t.Fatal("不可识别的输入应当返回 nil")
	}
}

func TestLyricAtSkipsEmptyLines(t *testing.T) {
	lines := []Line{
		{StartMs: 0, Text: "第一句"},
		{StartMs: 5000, Text: ""}, // 间奏
		{StartMs: 10000, Text: "第二句"},
		{StartMs: 15000, Text: "第三句"},
	}
	if cur, next := lyricAt(lines, 12000); cur != "第二句" || next != "第三句" {
		t.Fatalf("mid: %q / %q", cur, next)
	}
	// 落在空行上时沿用上一句，避免歌词区闪空
	if cur, _ := lyricAt(lines, 6000); cur != "第一句" {
		t.Fatalf("gap: %q", cur)
	}
	// 还没到第一句
	if cur, next := lyricAt(lines, -1); cur != "" || next != "第一句" {
		t.Fatalf("before: %q / %q", cur, next)
	}
	// 最后一句之后没有下一句
	if cur, next := lyricAt(lines, 99999); cur != "第三句" || next != "" {
		t.Fatalf("after: %q / %q", cur, next)
	}
}

// TestClientConnectsToFakeSplayer 用一个假 SPlayer 跑通"连上 -> 要歌曲信息 ->
// 定位当前歌词"的完整链路。
func TestClientConnectsToFakeSplayer(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	up := websocket.Upgrader{}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			if string(msg) == "PING" {
				_ = conn.WriteMessage(websocket.TextMessage, []byte("PONG"))
				continue
			}
			var m map[string]any
			_ = json.Unmarshal(msg, &m)
			if m["type"] == "get-song-info" {
				_ = conn.WriteMessage(websocket.TextMessage, []byte(songInfoJSON))
			}
		}
	})}
	go func() { _ = srv.Serve(ln) }()
	defer func() { _ = srv.Close() }()

	c := New(nil)
	defer c.Close()
	port := ln.Addr().(*net.TCPAddr).Port
	c.Touch(port)

	deadline := time.Now().Add(3 * time.Second)
	var snap Snapshot
	for time.Now().Before(deadline) {
		snap = c.Snapshot()
		if snap.Connected && snap.Lyric != "" {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !snap.Connected {
		t.Fatal("没有连上假 SPlayer")
	}
	if !snap.Playing || snap.Title != "Feel Something" || snap.Artist != "Jaymes Young" {
		t.Fatalf("曲目信息: %+v", snap)
	}
	if snap.Lyric != "Make me feel something, something" || snap.Next != "下一句" {
		t.Fatalf("歌词: %+v", snap)
	}
	// 12.5s + 播放中按墙钟插值的零头
	if snap.PositionMs < 12500 || snap.PositionMs > 12600 {
		t.Fatalf("进度应当是 12.5s -> 12500ms 上下，实为 %d", snap.PositionMs)
	}

	// 切歌：旧歌词立刻失效，等新的 lyric-change
	c.handle([]byte(`{"type":"song-change","data":{"name":"新歌","artist":"新歌手"}}`))
	if s := c.Snapshot(); s.Lyric != "" || s.Title != "新歌" {
		t.Fatalf("切歌后: %+v", s)
	}
	c.handle([]byte(`{"type":"lyric-change","data":{"lrcData":[{"startTime":0,"words":[{"word":"新歌词"}]}]}}`))
	if s := c.Snapshot(); s.Lyric != "新歌词" {
		t.Fatalf("新歌词: %+v", s)
	}
}
