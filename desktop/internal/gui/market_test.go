package gui

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/market"
	"hydrolink-desktop/internal/store"
)

const idxJSON = `{"schema":1,"generated_at":"2026-09-26T14:25:47Z","plugins":[
 {"id":"com.hydro.deskbuddy","name":"桌搭","author":"HydroLink","version":"0.9.0","runtime":"js",
  "entry":"server.js","permissions":["music"],"description":"圆屏播放界面","category":"utility",
  "icon":"p/i.png","url":"p/a.hpk",
  "sha256":"e39400db02ffa498fca7090738275bb0e6035121b06f91c4a7916af486b4ff8f","size":12985,
  "notes":"首个上架版本"},
 {"id":"com.demo.game","name":"小游戏","author":"me","version":"2.0.0","runtime":"js",
  "entry":"server.js","permissions":[],"description":"一个游戏","category":"game",
  "icon":"p/g.png","url":"p/g.hpk",
  "sha256":"1111111111111111111111111111111111111111111111111111111111111111","size":999,
  "min_hydrolink":"99.0.0"}]}`

// failingTransport 让所有请求失败（测试里保证不碰网络）
type failingTransport struct{}

func (failingTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	return nil, fmt.Errorf("测试里不发网络请求")
}

// newMarketApp 造一个只带数据目录的 App（不需要真的起 API server：市场逻辑只用
// store + 版本号）。
func newMarketApp(t *testing.T) (*App, *httptest.Server) {
	t.Helper()
	s, err := store.NewInDir(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(idxJSON))
	}))
	return &App{st: s, srv: api.New(s)}, ts
}

func TestMarketRefreshComputesRowsAndSources(t *testing.T) {
	a, ts := newMarketApp(t)
	defer ts.Close()

	// 让 fetch 一定失败（不联网、也不覆盖下面的 fixture 索引）：否则真联网拉到
	// 真实索引会把 fixture 顶掉，测试结果随网络变
	a.marketState().client = &market.Client{HTTPClient: &http.Client{Transport: failingTransport{}}}
	idx, err := market.ParseIndex([]byte(idxJSON))
	if err != nil {
		t.Fatal(err)
	}
	st := a.marketState()
	st.index = idx
	st.base = ts.URL

	view := a.MarketRefresh(MarketFilter{})
	if len(view.Rows) != 2 {
		t.Fatalf("应有 2 行，实际 %d", len(view.Rows))
	}
	// min_hydrolink=99 的那个应被判不兼容
	byID := map[string]market.Row{}
	for _, r := range view.Rows {
		byID[r.Entry.ID] = r
	}
	if byID["com.demo.game"].Status != market.StatusIncompatible {
		t.Fatalf("高版本要求应不兼容: %+v", byID["com.demo.game"])
	}
	if byID["com.hydro.deskbuddy"].Status != market.StatusAvailable {
		t.Fatalf("没装应可安装: %+v", byID["com.hydro.deskbuddy"])
	}
	if view.Counts["all"] != 2 || view.Counts["game"] != 1 || view.Counts["utility"] != 1 {
		t.Fatalf("分类计数不对: %+v", view.Counts)
	}
	if len(view.Sources) < 2 || view.Sources[0].Label != "自动" {
		t.Fatalf("来源选项不对: %+v", view.Sources)
	}
	// 搜索过滤
	filtered := a.MarketRefresh(MarketFilter{Query: "圆屏"})
	if len(filtered.Rows) != 1 || filtered.Rows[0].Entry.ID != "com.hydro.deskbuddy" {
		t.Fatalf("搜索过滤不对: %+v", filtered.Rows)
	}
}

func TestMarketViewJSONShape(t *testing.T) {
	// 前端按这些字段渲染，字段名变了前端就白屏 —— 钉住
	a, ts := newMarketApp(t)
	defer ts.Close()
	idx, _ := market.ParseIndex([]byte(idxJSON))
	a.marketState().index = idx
	data, err := json.Marshal(a.MarketRefresh(MarketFilter{}))
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{`"rows"`, `"counts"`, `"categories"`, `"sources"`, `"base"`, `"sourceLabel"`, `"fromCache"`, `"updatedAt"`} {
		if !strings.Contains(string(data), key) {
			t.Fatalf("视图 JSON 缺字段 %s: %s", key, data)
		}
	}
}

func TestMarketUpdatesAndDialogText(t *testing.T) {
	a, ts := newMarketApp(t)
	defer ts.Close()
	idx, _ := market.ParseIndex([]byte(idxJSON))
	a.marketState().index = idx
	a.marketState().base = "https://base"

	a.marketState().client = &market.Client{HTTPClient: &http.Client{Transport: failingTransport{}}}
	ups := a.MarketUpdates()
	if len(ups) != 0 {
		t.Fatalf("没装插件时不该有更新: %+v", ups)
	}
	text := a.MarketUpdateDialog("com.hydro.deskbuddy")
	if text != "" {
		t.Fatalf("没更新时正文应为空: %q", text)
	}
}
