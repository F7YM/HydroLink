// Package market 读取 GitHub 上的插件市场索引（仓 F7YM/hydromarket-index，schema 1），
// 并提供搜索/分类/更新判定与下载校验。与安卓端 market/ 是同一份协议的两次实现：
// 字段、必填项、分类取值、sha256 与相对路径的校验规则必须与索引仓的生成脚本一致。
package market

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Schema 是本客户端认识的索引版本。索引加字段不算破坏，升 schema 要两端一起发版。
const Schema = 1

// Categories 是分类受控表（与索引仓 scripts/build_index.py 保持一致）。
var Categories = []string{"game", "utility", "other"}

// CategoryAll 是「全部分类」筛选项的哨兵值。
const CategoryAll = "all"

// Entry 是索引里的一条插件记录。
//
// 字段分工（见索引仓 README）：id/name/author/version/runtime/entry/min_hydrolink/
// permissions 全部由生成脚本从 .hpk 的 manifest.json 解析，是包内事实的镜像；
// description/category/icon/keywords/screenshots/notes 是市场侧元数据（meta.json）。
type Entry struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Author       string   `json:"author"`
	Version      string   `json:"version"`
	Runtime      string   `json:"runtime"`
	Entry        string   `json:"entry"`
	MinHydroLink string   `json:"min_hydrolink"`
	Permissions  []string `json:"permissions"`
	Description  string   `json:"description"`
	Category     string   `json:"category"`
	Icon         string   `json:"icon"`
	URL          string   `json:"url"`
	SHA256       string   `json:"sha256"`
	Size         int64    `json:"size"`
	Keywords     []string `json:"keywords,omitempty"`
	Screenshots  []string `json:"screenshots,omitempty"`
	Homepage     string   `json:"homepage,omitempty"`
	// Notes 是这一版的更新日志（可选），更新弹窗会显示。
	Notes string `json:"notes,omitempty"`
}

// Index 是 index.json 的结构。
type Index struct {
	Schema      int     `json:"schema"`
	GeneratedAt string  `json:"generated_at"`
	Plugins     []Entry `json:"plugins"`
}

var sha256Re = regexp.MustCompile(`^[0-9a-f]{64}$`)

// ParseIndex 解析并校验索引。任一条不合规就整体报错——半个索引装起来比不装更危险。
func ParseIndex(data []byte) (*Index, error) {
	var idx Index
	if err := json.Unmarshal(data, &idx); err != nil {
		return nil, fmt.Errorf("索引不是合法 JSON: %w", err)
	}
	if idx.Schema != Schema {
		return nil, fmt.Errorf("索引版本不支持: %d（本客户端支持 %d）", idx.Schema, Schema)
	}
	if idx.Plugins == nil {
		return nil, fmt.Errorf("索引缺 plugins")
	}
	for i := range idx.Plugins {
		if err := idx.Plugins[i].validate(); err != nil {
			return nil, err
		}
	}
	return &idx, nil
}

func (e *Entry) validate() error {
	for _, req := range []struct{ field, value string }{
		{"id", e.ID},
		{"name", e.Name},
		{"version", e.Version},
		{"runtime", e.Runtime},
		{"description", e.Description},
		{"category", e.Category},
		{"icon", e.Icon},
		{"url", e.URL},
		{"sha256", e.SHA256},
	} {
		if strings.TrimSpace(req.value) == "" {
			return fmt.Errorf("索引条目缺必填字段 %s", req.field)
		}
	}
	if e.Size <= 0 {
		return fmt.Errorf("%s: size 必须是正整数", e.ID)
	}
	if !sha256Re.MatchString(e.SHA256) {
		return fmt.Errorf("%s: sha256 不是 64 位小写十六进制", e.ID)
	}
	if !contains(Categories, e.Category) {
		return fmt.Errorf("%s: category 只能是 %s（现在是 %s）", e.ID, strings.Join(Categories, "/"), e.Category)
	}
	// 相对路径：换镜像（jsDelivr / raw / gh-proxy）时索引不用改，也顺带挡掉索引里
	// 塞绝对 URL 把客户端指到别处去；`..` 一律拒（目录逃逸）。
	for _, p := range append([]string{e.Icon, e.URL}, e.Screenshots...) {
		if p == "" {
			continue
		}
		if strings.Contains(p, "://") || strings.HasPrefix(p, "/") {
			return fmt.Errorf("%s: 路径必须是仓库内相对路径（%s）", e.ID, p)
		}
		for _, seg := range strings.Split(p, "/") {
			if seg == ".." {
				return fmt.Errorf("%s: 路径不允许包含 ..（%s）", e.ID, p)
			}
		}
	}
	return nil
}

func contains(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}

// ---- 来源基址 ----

// Repo 是市场索引仓。
const Repo = "F7YM/hydromarket-index"

const rawBase = "https://raw.githubusercontent.com/" + Repo + "/main"

// AutoSetting 表示「依次尝试候选基址」。
const AutoSetting = "auto"

// Candidates 是自动模式的尝试顺序：**jsDelivr → 直连 raw → gh-proxy**。
// 首选 jsDelivr：raw.githubusercontent 在国内经常连不上；直连排第二（网络好时更快）。
var Candidates = []string{
	"https://cdn.jsdelivr.net/gh/" + Repo + "@main",
	rawBase,
	"https://gh-proxy.org/" + rawBase,
}

var candidateLabels = []string{"jsDelivr", "GitHub Raw", "Gh-Proxy"}

// Bases 把配置值变成要试的基址列表：空/auto → 候选表；否则就用户填的那一个。
func Bases(setting string) []string {
	s := strings.TrimSpace(setting)
	if s == "" || s == AutoSetting {
		return Candidates
	}
	return []string{strings.TrimRight(s, "/")}
}

// SourceOption 是「市场来源」弹窗里的一个选项（值 = 配置里存的基址/auto）。
type SourceOption struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

// SourceOptions 返回可选项：自动 + 三个候选。**由 Go 提供**，前端只渲染——
// 前端自己抄一份基址表的话，加候选时两边必漂。
func SourceOptions(setting string) []SourceOption {
	out := make([]SourceOption, 0, len(Candidates)+1)
	current := strings.TrimSpace(setting)
	if current == "" {
		current = AutoSetting
	}
	out = append(out, SourceOption{Value: AutoSetting, Label: "自动"})
	for _, c := range Candidates {
		out = append(out, SourceOption{Value: c, Label: Label(c)})
	}
	// 当前设置不在候选里（用户填了自建反代）→ 额外列一项，别把用户的地址弄丢了
	known := false
	for _, o := range out {
		if o.Value == current {
			known = true
			break
		}
	}
	if !known {
		out = append(out, SourceOption{Value: current, Label: current})
	}
	return out
}

// Label 是来源在 UI 上的短名（别把一长串 URL 甩给用户看）。
func Label(setting string) string {
	s := strings.TrimSpace(setting)
	if s == "" || s == AutoSetting {
		return "自动"
	}
	for i, c := range Candidates {
		if c == s || strings.TrimRight(c, "/") == strings.TrimRight(s, "/") {
			return candidateLabels[i]
		}
	}
	return s
}

// Join 把基址与仓库内相对路径拼成绝对 URL。
func Join(base, path string) string {
	return strings.TrimRight(base, "/") + "/" + strings.TrimLeft(path, "/")
}

// ---- 拉取 / 下载 ----

// Result 是一次 fetch 的结果。Index 为 nil 表示彻底没拿到（连缓存都没有）。
type Result struct {
	Index     *Index
	Base      string
	FromCache bool
	UpdatedAt string
	// Err 非空表示所有基址都失败了：Err 里有原因，Index 可能来自本地缓存（离线可用）。
	Err string
}

// Client 拉索引/下载包。HTTPClient 可注入（单测用假 fetcher）。
type Client struct {
	HTTPClient *http.Client
	CacheDir   string
}

func NewClient(cacheDir string) *Client {
	return &Client{
		HTTPClient: &http.Client{Timeout: 20 * time.Second},
		CacheDir:   cacheDir,
	}
}

type cacheMeta struct {
	ETag      string `json:"etag"`
	Base      string `json:"base"`
	UpdatedAt string `json:"updatedAt"`
}

// Fetch 按基址顺序试索引：首个 200 且合法的胜出；全失败时回落到本地缓存（离线也能看）。
func (c *Client) Fetch(bases []string) Result {
	if c.HTTPClient == nil {
		c.HTTPClient = &http.Client{Timeout: 20 * time.Second}
	}
	cachedIndex, cachedMeta := c.readCache()
	etag := ""
	if cachedMeta != nil {
		etag = cachedMeta.ETag
	}

	lastErr := "无法获取插件市场"
	for _, base := range bases {
		req, err := http.NewRequest(http.MethodGet, Join(base, "index.json"), nil)
		if err != nil {
			lastErr = fmt.Sprintf("%s: %v", base, err)
			continue
		}
		if etag != "" {
			req.Header.Set("If-None-Match", etag)
		}
		resp, err := c.HTTPClient.Do(req)
		if err != nil {
			lastErr = fmt.Sprintf("%s: %v", base, err)
			continue
		}
		if resp.StatusCode == http.StatusNotModified && cachedIndex != nil {
			resp.Body.Close()
			return Result{Index: cachedIndex, Base: base, FromCache: true, UpdatedAt: cachedMeta.UpdatedAt}
		}
		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			lastErr = fmt.Sprintf("%s: HTTP %d", base, resp.StatusCode)
			continue
		}
		body, readErr := io.ReadAll(resp.Body)
		resp.Body.Close()
		if readErr != nil {
			lastErr = fmt.Sprintf("%s: %v", base, readErr)
			continue
		}
		idx, parseErr := ParseIndex(body)
		if parseErr != nil {
			lastErr = fmt.Sprintf("%s: %v", base, parseErr)
			continue
		}
		now := time.Now().UTC().Format(time.RFC3339)
		c.writeCache(idx, resp.Header.Get("ETag"), base, now)
		return Result{Index: idx, Base: base, UpdatedAt: now}
	}

	if cachedIndex != nil {
		return Result{
			Index:     cachedIndex,
			Base:      cachedMeta.Base,
			FromCache: true,
			UpdatedAt: cachedMeta.UpdatedAt,
			Err:       lastErr + "（显示的是上次缓存）",
		}
	}
	return Result{Err: lastErr}
}

// Download 下载 .hpk 并校验 sha256 + size。返回错误信息时 dest 已被删除。
func (c *Client) Download(base string, e Entry, dest string) error {
	if c.HTTPClient == nil {
		c.HTTPClient = &http.Client{Timeout: 20 * time.Second}
	}
	resp, err := c.HTTPClient.Get(Join(base, e.URL))
	if err != nil {
		return fmt.Errorf("下载失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("下载失败: HTTP %d", resp.StatusCode)
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return fmt.Errorf("下载失败: %w", err)
	}
	f, err := os.Create(dest)
	if err != nil {
		return fmt.Errorf("下载失败: %w", err)
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		f.Close()
		os.Remove(dest)
		return fmt.Errorf("下载失败: %w", err)
	}
	if err := f.Close(); err != nil {
		os.Remove(dest)
		return fmt.Errorf("下载失败: %w", err)
	}
	return Verify(dest, e)
}

// Verify 校验已下载文件；不过就删掉并说明差在哪（半截文件留着只会更麻烦）。
func Verify(path string, e Entry) error {
	st, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("校验失败: 文件不存在")
	}
	if st.Size() != e.Size {
		os.Remove(path)
		return fmt.Errorf("校验失败：大小对不上（索引 %d，实际 %d）", e.Size, st.Size())
	}
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("校验失败: %w", err)
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return fmt.Errorf("校验失败: %w", err)
	}
	got := hex.EncodeToString(h.Sum(nil))
	if got != e.SHA256 {
		os.Remove(path)
		return fmt.Errorf("校验失败：指纹对不上（索引 %s，实际 %s）", ShortHash(e.SHA256), ShortHash(got))
	}
	return nil
}

func (c *Client) readCache() (*Index, *cacheMeta) {
	if c.CacheDir == "" {
		return nil, nil
	}
	data, err := os.ReadFile(filepath.Join(c.CacheDir, "index.json"))
	if err != nil {
		return nil, nil
	}
	idx, err := ParseIndex(data)
	if err != nil {
		return nil, nil // 缓存坏了就当没有：下次 fetch 会覆盖
	}
	metaData, err := os.ReadFile(filepath.Join(c.CacheDir, "index.meta"))
	if err != nil {
		return nil, nil
	}
	var meta cacheMeta
	if err := json.Unmarshal(metaData, &meta); err != nil {
		return nil, nil
	}
	return idx, &meta
}

func (c *Client) writeCache(idx *Index, etag, base, updatedAt string) {
	if c.CacheDir == "" {
		return
	}
	if err := os.MkdirAll(c.CacheDir, 0o755); err != nil {
		return
	}
	if data, err := json.Marshal(idx); err == nil {
		_ = os.WriteFile(filepath.Join(c.CacheDir, "index.json"), data, 0o644)
	}
	if data, err := json.Marshal(cacheMeta{ETag: etag, Base: base, UpdatedAt: updatedAt}); err == nil {
		_ = os.WriteFile(filepath.Join(c.CacheDir, "index.meta"), data, 0o644)
	}
}

// ---- 状态 / 搜索 / 排序 ----

// Status 是「这个插件在本地已装视角下」的状态。
type Status string

const (
	StatusAvailable    Status = "available"
	StatusInstalled    Status = "installed"
	StatusUpdate       Status = "update"
	StatusIncompatible Status = "incompatible"
)

// Row 是列表一行：索引条目 + 本地状态。
type Row struct {
	Entry            Entry  `json:"entry"`
	Status           Status `json:"status"`
	InstalledVersion string `json:"installedVersion"`
	ActionLabel      string `json:"actionLabel"`
}

// StatusOf 判定状态。hostVersion 用于 min_hydrolink 检查。
func StatusOf(e Entry, installed string, hostVersion string) Status {
	if !HostVersionCovers(hostVersion, e.MinHydroLink) {
		return StatusIncompatible
	}
	if installed == "" {
		return StatusAvailable
	}
	if CompareVersions(e.Version, installed) > 0 {
		return StatusUpdate
	}
	return StatusInstalled
}

// HostVersionCovers 宿主版本是否满足插件的 min_hydrolink（空 = 不限）。
func HostVersionCovers(hostVersion, minHydroLink string) bool {
	if strings.TrimSpace(minHydroLink) == "" {
		return true
	}
	return CompareVersions(hostVersion, minHydroLink) >= 0
}

// ActionLabel 是行内按钮文案。
func ActionLabel(s Status) string {
	switch s {
	case StatusUpdate:
		return "更新"
	case StatusInstalled:
		return "重新安装"
	case StatusIncompatible:
		return "不兼容"
	default:
		return "安装"
	}
}

var statusRank = map[Status]int{
	StatusInstalled:    0, // 已装 → 可更新 → 未装 → 不兼容
	StatusUpdate:       1,
	StatusAvailable:    2,
	StatusIncompatible: 3,
}

// Rows 生成排好序的全部行：已装 → 可更新 → 未装 → 不兼容，同级按 id 稳定。
func Rows(idx *Index, installed map[string]string, hostVersion string) []Row {
	if idx == nil {
		return nil
	}
	rows := make([]Row, 0, len(idx.Plugins))
	for _, e := range idx.Plugins {
		cur := installed[e.ID]
		st := StatusOf(e, cur, hostVersion)
		rows = append(rows, Row{Entry: e, Status: st, InstalledVersion: cur, ActionLabel: ActionLabel(st)})
	}
	sort.SliceStable(rows, func(i, j int) bool {
		ri, rj := statusRank[rows[i].Status], statusRank[rows[j].Status]
		if ri != rj {
			return ri < rj
		}
		return rows[i].Entry.ID < rows[j].Entry.ID
	})
	return rows
}

// Filter 按分类与搜索词过滤（子串、大小写不敏感）。
func Filter(rows []Row, query, category string) []Row {
	out := make([]Row, 0, len(rows))
	for _, r := range rows {
		if category != "" && category != CategoryAll && r.Entry.Category != category {
			continue
		}
		if !Matches(r.Entry, query) {
			continue
		}
		out = append(out, r)
	}
	return out
}

// Counts 是分类 chip 的计数（不受分类筛选影响，只受搜索影响）。
func Counts(rows []Row, query string) map[string]int {
	searched := make([]Row, 0, len(rows))
	for _, r := range rows {
		if Matches(r.Entry, query) {
			searched = append(searched, r)
		}
	}
	out := map[string]int{CategoryAll: len(searched)}
	for _, c := range Categories {
		out[c] = 0
	}
	for _, r := range searched {
		out[r.Entry.Category]++
	}
	return out
}

// Matches 搜索：名称/包名/作者/描述/关键词。
func Matches(e Entry, query string) bool {
	q := strings.TrimSpace(query)
	if q == "" {
		return true
	}
	fields := append([]string{e.Name, e.ID, e.Author, e.Description}, e.Keywords...)
	for _, f := range fields {
		if strings.Contains(strings.ToLower(f), strings.ToLower(q)) {
			return true
		}
	}
	return false
}

// ---- 更新 ----

// Update 是一条「已装插件有新版」的结果。
type Update struct {
	Entry          Entry  `json:"entry"`
	CurrentVersion string `json:"currentVersion"`
	Compatible     bool   `json:"compatible"`
}

// Updates 拿索引与已装列表比版本：只报「索引更新」的，禁降级仍由安装时兜底。
func Updates(idx *Index, installed map[string]string, hostVersion string) []Update {
	if idx == nil {
		return nil
	}
	var out []Update
	for _, e := range idx.Plugins {
		cur, ok := installed[e.ID]
		if !ok || cur == "" {
			continue
		}
		if CompareVersions(e.Version, cur) <= 0 {
			continue
		}
		out = append(out, Update{
			Entry:          e,
			CurrentVersion: cur,
			Compatible:     HostVersionCovers(hostVersion, e.MinHydroLink),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Entry.ID < out[j].Entry.ID })
	return out
}

// UpdateDialogText 是更新弹窗正文：版本变化 + 大小/运行时/权限 + 更新日志；
// 宿主版本不够时写明差在哪（让用户看得到更新内容，但装不了）。
func UpdateDialogText(u Update, base, hostVersion string) string {
	var b strings.Builder
	e := u.Entry
	fmt.Fprintf(&b, "新版本：v%s（当前 v%s）\n", e.Version, u.CurrentVersion)
	fmt.Fprintf(&b, "运行时：%s\n", e.Runtime)
	fmt.Fprintf(&b, "大小：%d KB\n", e.Size/1024)
	if len(e.Permissions) == 0 {
		b.WriteString("权限：未申请权限\n")
	} else {
		fmt.Fprintf(&b, "权限：%s\n", strings.Join(e.Permissions, ", "))
	}
	if base == "" {
		base = "—"
	}
	fmt.Fprintf(&b, "来源：%s\n", base)
	fmt.Fprintf(&b, "指纹：%s\n", ShortHash(e.SHA256))
	b.WriteString("\n")
	if !u.Compatible {
		fmt.Fprintf(&b, "HydroLink 版本过低：需要 v%s 及以上，当前 v%s。升级 HydroLink 后才能装。\n\n", e.MinHydroLink, hostVersion)
	}
	b.WriteString("更新日志：\n")
	if strings.TrimSpace(e.Notes) == "" {
		b.WriteString("这一版没有提供更新日志。")
	} else {
		b.WriteString(e.Notes)
	}
	return b.String()
}

// ShortHash 取指纹前 12 位（弹窗里给人看的）。别直接切 e.SHA256[:12]——
// 索引虽已校验过，但这里仍可能被非索引来源调用，空串会 panic。
func ShortHash(s string) string {
	if len(s) <= 12 {
		return s
	}
	return s[:12]
}

// ---- 版本比较（与 store.CompareVersions 同规则） ----

// CompareVersions 比较点分版本：缺段按 0、预发布低于正式、忽略 +build。
// 与 store.CompareVersions 的差别是它额外处理预发布后缀；两处都要与索引仓脚本一致。
func CompareVersions(a, b string) int {
	na, pa, ha := splitVersion(a)
	nb, pb, hb := splitVersion(b)
	n := len(na)
	if len(nb) > n {
		n = len(nb)
	}
	for i := 0; i < n; i++ {
		av, bv := 0, 0
		if i < len(na) {
			av = na[i]
		}
		if i < len(nb) {
			bv = nb[i]
		}
		if av != bv {
			if av < bv {
				return -1
			}
			return 1
		}
	}
	if ha != hb {
		if ha {
			return -1 // 预发布低于正式
		}
		return 1
	}
	if pa != pb {
		if pa == "" {
			return 1
		}
		if pb == "" {
			return -1
		}
		if pa < pb {
			return -1
		}
		return 1
	}
	return 0
}

func splitVersion(v string) ([]int, string, bool) {
	v = strings.TrimSpace(v)
	v = strings.TrimLeft(v, "vV")
	if i := strings.Index(v, "+"); i >= 0 {
		v = v[:i] // build metadata 不参与比较
	}
	pre := ""
	if i := strings.Index(v, "-"); i >= 0 {
		pre = v[i+1:]
		v = v[:i]
	}
	nums := []int{}
	for _, part := range strings.Split(v, ".") {
		digits := strings.Map(func(r rune) rune {
			if r >= '0' && r <= '9' {
				return r
			}
			return -1
		}, part)
		if digits == "" {
			nums = append(nums, 0)
			continue
		}
		n, err := strconv.Atoi(digits)
		if err != nil {
			nums = append(nums, 0)
			continue
		}
		nums = append(nums, n)
	}
	return nums, pre, pre != ""
}
