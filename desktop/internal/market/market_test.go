package market

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const goodIndex = `{"schema":1,"generated_at":"2026-09-26T14:25:47Z","plugins":[
  {"id":"com.hydro.deskbuddy","name":"桌搭","author":"HydroLink","version":"0.9.0","runtime":"js",
   "entry":"server.js","min_hydrolink":"","permissions":["music","storage"],
   "description":"正在播放的圆屏界面","category":"utility",
   "icon":"plugins/com.hydro.deskbuddy/0.9.0/icon.png",
   "url":"plugins/com.hydro.deskbuddy/0.9.0/com.hydro.deskbuddy.hpk",
   "sha256":"e39400db02ffa498fca7090738275bb0e6035121b06f91c4a7916af486b4ff8f","size":12985,
   "keywords":["音乐","歌词"],"notes":"首个上架版本"}]}`

// ---- 解析与校验 ----

func TestParseIndexRealShape(t *testing.T) {
	idx, err := ParseIndex([]byte(goodIndex))
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(idx.Plugins) != 1 {
		t.Fatalf("应有 1 条，实际 %d", len(idx.Plugins))
	}
	e := idx.Plugins[0]
	if e.ID != "com.hydro.deskbuddy" || e.Name != "桌搭" || e.Version != "0.9.0" {
		t.Fatalf("字段解析不对: %+v", e)
	}
	if e.Category != "utility" || e.Size != 12985 {
		t.Fatalf("分类/大小不对: %+v", e)
	}
	if len(e.Permissions) != 2 || e.Permissions[0] != "music" {
		t.Fatalf("权限不对: %+v", e.Permissions)
	}
	if e.Notes != "首个上架版本" || len(e.Keywords) != 2 {
		t.Fatalf("市场元数据不对: %+v", e)
	}
}

func TestParseIndexRejects(t *testing.T) {
	cases := []struct {
		name string
		json string
		want string
	}{
		{"schema 不认识", strings.Replace(goodIndex, `"schema":1`, `"schema":2`, 1), "索引版本不支持"},
		{"缺 plugins", `{"schema":1}`, "缺 plugins"},
		{"不是 JSON", "not json", "不是合法 JSON"},
		{"sha256 大写", strings.Replace(goodIndex, "e39400db", "E39400DB", 1), "sha256"},
		{"sha256 太短", strings.Replace(goodIndex, "e39400db02", "abc", 1), "sha256"},
		{"size 非正", strings.Replace(goodIndex, `"size":12985`, `"size":0`, 1), "size"},
		{"category 非法", strings.Replace(goodIndex, `"utility"`, `"weather"`, 1), "category"},
		{"icon 绝对 URL", strings.Replace(goodIndex, `"plugins/com.hydro.deskbuddy/0.9.0/icon.png"`, `"https://evil.invalid/i.png"`, 1), "相对路径"},
		{"url 绝对路径", strings.Replace(goodIndex, `"plugins/com.hydro.deskbuddy/0.9.0/com.hydro.deskbuddy.hpk"`, `"/abs/a.hpk"`, 1), "相对路径"},
		{"目录逃逸", strings.Replace(goodIndex, "plugins/com.hydro.deskbuddy/0.9.0/icon.png", "../../etc/icon.png", 1), ".."},
		{"缺 description", strings.Replace(goodIndex, `"description":"正在播放的圆屏界面",`, ``, 1), "description"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := ParseIndex([]byte(c.json))
			if err == nil {
				t.Fatalf("应当拒绝（%s）", c.want)
			}
			if !strings.Contains(err.Error(), c.want) {
				t.Fatalf("错误信息应含 %q，实际 %q", c.want, err.Error())
			}
		})
	}
}

func TestParseIndexEmptyPlugins(t *testing.T) {
	idx, err := ParseIndex([]byte(`{"schema":1,"generated_at":"","plugins":[]}`))
	if err != nil {
		t.Fatalf("空列表应合法: %v", err)
	}
	if len(idx.Plugins) != 0 {
		t.Fatalf("应为空")
	}
}

// ---- 基址表 ----

func TestBasesAndLabels(t *testing.T) {
	if len(Bases(AutoSetting)) != 3 || len(Bases("")) != 3 {
		t.Fatalf("auto/空 应走候选表")
	}
	if got := Bases("https://my.mirror/x/"); len(got) != 1 || got[0] != "https://my.mirror/x" {
		t.Fatalf("自定义基址应去尾斜杠: %+v", got)
	}
	if !strings.Contains(Candidates[0], "jsdelivr") {
		t.Fatalf("默认首选应是 jsDelivr: %+v", Candidates)
	}
	if Label(AutoSetting) != "自动" {
		t.Fatalf("auto 短名应为自动")
	}
	if Label(Candidates[0]) != "jsDelivr" || Label(Candidates[1]) != "GitHub Raw" || Label(Candidates[2]) != "Gh-Proxy" {
		t.Fatalf("候选短名不对: %s %s %s", Label(Candidates[0]), Label(Candidates[1]), Label(Candidates[2]))
	}
	if Label("https://my.mirror/x") != "https://my.mirror/x" {
		t.Fatalf("自定义地址原样显示")
	}
	if Join("https://a/", "/index.json") != "https://a/index.json" {
		t.Fatalf("Join 拼接不对")
	}
}

// ---- fetch：failover / 304 / 缓存 ----

func TestFetchFailsOverToNextBase(t *testing.T) {
	// 第一个基址真的答 500（用 httptest 而不是假域名：假域名压根到不了服务端，
	// 那样测的是 DNS 失败而不是「基址返回了错误」）
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer bad.Close()
	var goodHits int
	good := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		goodHits++
		w.Header().Set("ETag", "e1")
		fmt.Fprint(w, goodIndex)
	}))
	defer good.Close()

	c := NewClient(t.TempDir())
	res := c.Fetch([]string{bad.URL, good.URL})
	if res.Index == nil {
		t.Fatalf("应拿到索引: %+v", res)
	}
	if res.Base != good.URL {
		t.Fatalf("应选中第二个基址，实际 %q", res.Base)
	}
	if res.FromCache {
		t.Fatalf("刚拉的索引不该算缓存")
	}
	if goodHits != 1 {
		t.Fatalf("成功的基址应只被请求一次，实际 %d", goodHits)
	}
}

func TestFetchSkipsBaseWithInvalidIndex(t *testing.T) {
	// HTTP 200 但内容不是合法索引 → 同样要换下一个
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"schema":99,"plugins":[]}`)
	}))
	defer bad.Close()
	good := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, goodIndex)
	}))
	defer good.Close()

	res := NewClient(t.TempDir()).Fetch([]string{bad.URL, good.URL})
	if res.Index == nil || res.Base != good.URL {
		t.Fatalf("非法索引应被跳过并换下一个: %+v", res)
	}
}

func TestFetch304UsesCache(t *testing.T) {
	dir := t.TempDir()
	var served int
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		served++
		if served == 1 {
			w.Header().Set("ETag", "e1")
			fmt.Fprint(w, goodIndex)
			return
		}
		if r.Header.Get("If-None-Match") != "e1" {
			t.Errorf("第二次请求应带 If-None-Match，实际 %q", r.Header.Get("If-None-Match"))
		}
		w.WriteHeader(http.StatusNotModified)
	}))
	defer ts.Close()

	c := NewClient(dir)
	c.Fetch([]string{ts.URL})
	res := c.Fetch([]string{ts.URL})
	if res.Index == nil || !res.FromCache {
		t.Fatalf("304 应回落缓存: %+v", res)
	}
}

func TestFetchFallsBackToCacheWhenAllBasesFail(t *testing.T) {
	dir := t.TempDir()
	ok := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, goodIndex)
	}))
	NewClient(dir).Fetch([]string{ok.URL})
	ok.Close()

	c := NewClient(dir)
	res := c.Fetch([]string{"https://gone.invalid"})
	if res.Index == nil {
		t.Fatalf("断网时应回落缓存")
	}
	if !res.FromCache || res.Err == "" || !strings.Contains(res.Err, "缓存") {
		t.Fatalf("应说明显示的是缓存: %+v", res)
	}
}

func TestFetchAllFailNoCache(t *testing.T) {
	c := NewClient(t.TempDir())
	res := c.Fetch([]string{"https://a.invalid", "https://b.invalid"})
	if res.Index != nil || res.Err == "" {
		t.Fatalf("全失败应无索引且有错误: %+v", res)
	}
}

func TestBrokenCacheIsIgnored(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "index.json"), []byte("garbage"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "index.meta"), []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	res := NewClient(dir).Fetch([]string{"https://a.invalid"})
	if res.Index != nil {
		t.Fatalf("坏缓存应被忽略")
	}
}

// ---- 下载与校验 ----

func TestDownloadVerifiesSHA256(t *testing.T) {
	payload := []byte("hello")
	sum := sha256Hex(payload)
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write(payload)
	}))
	defer ts.Close()

	dir := t.TempDir()
	dest := filepath.Join(dir, "a.hpk")
	entry := Entry{URL: "p/a.hpk", SHA256: sum, Size: int64(len(payload))}
	if err := NewClient(dir).Download(ts.URL, entry, dest); err != nil {
		t.Fatalf("下载+校验应通过: %v", err)
	}
	if _, err := os.Stat(dest); err != nil {
		t.Fatalf("文件应保留")
	}
}

func TestVerifyRejectsSizeAndHash(t *testing.T) {
	dir := t.TempDir()
	dest := filepath.Join(dir, "a.hpk")
	if err := os.WriteFile(dest, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}

	err := Verify(dest, Entry{SHA256: sha256Hex([]byte("hello")), Size: 999})
	if err == nil || !strings.Contains(err.Error(), "大小") {
		t.Fatalf("大小不符应报错: %v", err)
	}
	if _, statErr := os.Stat(dest); statErr == nil {
		t.Fatalf("校验不过的文件应删掉")
	}

	_ = os.WriteFile(dest, []byte("hello"), 0o644)
	err = Verify(dest, Entry{SHA256: strings.Repeat("0", 64), Size: 5})
	if err == nil || !strings.Contains(err.Error(), "指纹") {
		t.Fatalf("指纹不符应报错: %v", err)
	}
	if _, statErr := os.Stat(dest); statErr == nil {
		t.Fatalf("校验不过的文件应删掉")
	}
}

// sha256Hex 用标准库算期望值（与 market.Verify 同一个算法）
func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// ---- 状态 / 搜索 / 更新 ----

func entry(id, version, category string, minHydroLink string, notes string) Entry {
	return Entry{
		ID: id, Name: "插件" + id, Author: "作者", Version: version, Runtime: "js",
		MinHydroLink: minHydroLink, Category: category, Description: "描述",
		Icon: "i.png", URL: "a.hpk", Size: 20480, Permissions: []string{"storage"},
		Notes: notes,
	}
}

func TestStatusAndActionLabels(t *testing.T) {
	e := entry("a", "1.2.0", "utility", "", "")
	if got := StatusOf(e, "", "1.1.0"); got != StatusAvailable {
		t.Fatalf("没装应可安装，实际 %s", got)
	}
	if got := StatusOf(e, "1.2.0", "1.1.0"); got != StatusInstalled {
		t.Fatalf("同版应已安装，实际 %s", got)
	}
	if got := StatusOf(e, "1.1.0", "1.1.0"); got != StatusUpdate {
		t.Fatalf("索引版本更高应可更新，实际 %s", got)
	}
	low := entry("b", "1.0.0", "other", "2.0.0", "")
	if got := StatusOf(low, "", "1.1.0"); got != StatusIncompatible {
		t.Fatalf("宿主版本不够应不兼容，实际 %s", got)
	}
	if ActionLabel(StatusAvailable) != "安装" || ActionLabel(StatusUpdate) != "更新" ||
		ActionLabel(StatusInstalled) != "重新安装" || ActionLabel(StatusIncompatible) != "不兼容" {
		t.Fatalf("按钮文案不对")
	}
}

func TestRowsSortAndFilter(t *testing.T) {
	idx := &Index{Schema: 1, Plugins: []Entry{
		entry("z.new", "1.0.0", "game", "", ""),
		entry("m.old", "1.0.0", "utility", "", ""),
		entry("a.update", "2.0.0", "game", "", ""),
		entry("b.same", "1.0.0", "game", "", ""),
	}}
	installed := map[string]string{"m.old": "1.0.0", "a.update": "1.0.0", "b.same": "1.0.0"}
	rows := Rows(idx, installed, "1.1.0")
	want := []string{"b.same", "m.old", "a.update", "z.new"}
	if len(rows) != len(want) {
		t.Fatalf("行数不对: %d", len(rows))
	}
	for i, id := range want {
		if rows[i].Entry.ID != id {
			t.Fatalf("第 %d 行应为 %s，实际 %s", i, id, rows[i].Entry.ID)
		}
	}
	counts := Counts(rows, "")
	if counts[CategoryAll] != 4 || counts["game"] != 3 || counts["utility"] != 1 || counts["other"] != 0 {
		t.Fatalf("分类计数不对: %+v", counts)
	}
	if got := Filter(rows, "", "game"); len(got) != 3 {
		t.Fatalf("分类过滤不对: %d", len(got))
	}
	if !Matches(idx.Plugins[0], "") {
		t.Fatalf("空查询应全匹配")
	}
	e := idx.Plugins[0]
	e.Name = "示例天气"
	if !Matches(e, "天气") || !Matches(e, "示例") {
		t.Fatalf("名称/描述搜索失败")
	}
	e.Keywords = []string{"下雨"}
	if !Matches(e, "下雨") {
		t.Fatalf("关键词搜索失败")
	}
}

func TestUpdatesAndDialogText(t *testing.T) {
	idx := &Index{Schema: 1, Plugins: []Entry{
		entry("a", "1.2.0", "utility", "", "修了歌词错行"),
		entry("b", "1.0.0", "utility", "", ""),
		entry("c", "2.0.0", "utility", "2.0.0", ""),
		entry("d", "1.0.0", "utility", "", ""),
	}}
	updates := Updates(idx, map[string]string{"a": "1.1.0", "b": "1.0.0", "c": "1.0.0", "d": "2.0.0"}, "1.1.0")
	if len(updates) != 2 {
		t.Fatalf("应只有 a 与 c 可更新，实际 %d: %+v", len(updates), updates)
	}
	if updates[0].Entry.ID != "a" || updates[0].CurrentVersion != "1.1.0" || !updates[0].Compatible {
		t.Fatalf("a 的更新信息不对: %+v", updates[0])
	}
	if updates[1].Entry.ID != "c" || updates[1].Compatible {
		t.Fatalf("c 应标记为不兼容: %+v", updates[1])
	}
	text := UpdateDialogText(updates[0], "https://a", "1.1.0")
	for _, want := range []string{"v1.2.0", "v1.1.0", "修了歌词错行", "https://a", "storage"} {
		if !strings.Contains(text, want) {
			t.Fatalf("更新弹窗正文应含 %q，实际 %q", want, text)
		}
	}
	low := UpdateDialogText(updates[1], "", "1.1.0")
	if !strings.Contains(low, "HydroLink 版本过低") || !strings.Contains(low, "2.0.0") {
		t.Fatalf("版本过低应写明差在哪: %q", low)
	}
	if !strings.Contains(low, "没有提供更新日志") {
		t.Fatalf("没有 notes 时应有兜底文案: %q", low)
	}
}

func TestCompareVersionsMatchesClientRules(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"1.0", "1.0.0", 0},
		{"1.0.0-beta", "1.0.0", -1},
		{"0.9.0", "0.10.0", -1},
		{"1.2.3+build", "1.2.3", 0},
		{"2.0.0-rc.1", "2.0.0-rc.2", -1},
		{"v1.0.1", "1.0.0", 1},
	}
	for _, c := range cases {
		if got := CompareVersions(c.a, c.b); got != c.want {
			t.Fatalf("CompareVersions(%q,%q)=%d 期望 %d", c.a, c.b, got, c.want)
		}
	}
}

func TestIndexJSONRoundTripKeepsOptionalFields(t *testing.T) {
	idx, err := ParseIndex([]byte(goodIndex))
	if err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(idx)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"notes"`) || !strings.Contains(string(data), `"keywords"`) {
		t.Fatalf("可选字段应被保留: %s", data)
	}
	again, err := ParseIndex(data)
	if err != nil || again.Plugins[0].Notes != idx.Plugins[0].Notes {
		t.Fatalf("往返后不该丢字段: %v", err)
	}
}
