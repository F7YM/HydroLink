package gui

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/market"
	"hydrolink-desktop/internal/store"
)

// 插件市场（Wails 绑定）。
//
// 数据来自 GitHub 索引仓 F7YM/hydromarket-index：**桌面端自己出网拉**，服务端不参与，
// 也没有新增任何 HTTP 路由——与安卓端 market/ 是同一份协议的两套实现（字段、必填项、
// 分类取值、sha256/相对路径的校验规则必须与索引仓生成脚本一致）。
//
// 安装链路复用既有的两段式：MarketFetchPackage 下载+校验后返回清单预览 → 前端用**同一个**
// 确认弹窗问用户 → MarketInstallPackage 才真正落盘。UI 只负责渲染，判定逻辑都在 Go 侧，
// 免得前后端各写一套版本比较。

// MarketView 是市场面板一次刷新的完整数据（前端只渲染，不再算状态）。
type MarketView struct {
	Rows       []market.Row   `json:"rows"`
	Counts     map[string]int `json:"counts"`
	Categories []string       `json:"categories"`
	// Sources 是可选项（自动 + 候选 + 用户自填），由 Go 提供，前端只渲染
	Sources     []market.SourceOption `json:"sources"`
	Base        string                `json:"base"`
	Source      string                `json:"source"`
	SourceLabel string                `json:"sourceLabel"`
	FromCache   bool                  `json:"fromCache"`
	UpdatedAt   string                `json:"updatedAt"`
	// Err 非空表示拉取失败（可能显示的是上次缓存），用系统提示呈现。
	Err string `json:"err,omitempty"`
	// Staged 是刚下载待确认的包路径（空 = 没有待确认的包）。
	Staged string `json:"staged,omitempty"`
}

// MarketFilter 是前端传回来的搜索/分类条件（空 = 不过滤）。
type MarketFilter struct {
	Query    string `json:"query"`
	Category string `json:"category"`
}

// MarketStage 是「已下载待确认」的一个包：确认弹窗要展示它的清单与来源。
type MarketStage struct {
	Path    string           `json:"-"`
	Entry   market.Entry     `json:"entry"`
	Preview store.PluginInfo `json:"preview"`
	Base    string           `json:"base"`
}

// marketState 记着当前查看的索引（搜索/分类都在内存里做）与待确认的包。
type marketState struct {
	mu     sync.Mutex
	index  *market.Index
	base   string
	stage  *MarketStage
	client *market.Client
}

func (a *App) marketState() *marketState {
	a.marketOnce.Do(func() {
		a.market = &marketState{
			client: market.NewClient(filepath.Join(a.st.Dir(), "marketplace")),
		}
	})
	return a.market
}

// marketRefreshMinInterval 限制自动刷新频率（市场面板打开时才拉；轮询不重复打网络）。
const marketRefreshMinInterval = 30 * time.Second

// MarketRefresh 拉一次索引并返回过滤后的视图。
func (a *App) MarketRefresh(filter MarketFilter) MarketView {
	cfg, _ := a.st.LoadConfig()
	bases := market.Bases(cfg.MarketSource)
	res := a.marketState().client.Fetch(bases)

	ms := a.marketState()
	ms.mu.Lock()
	if res.Index != nil {
		ms.index = res.Index
		ms.base = res.Base
	}
	idx, base := ms.index, ms.base
	stage := ms.stage
	ms.mu.Unlock()

	installed := a.installedVersions()
	rows := market.Rows(idx, installed, api.Version)
	if filter.Category != "" && filter.Category != market.CategoryAll {
		rows = market.Filter(rows, filter.Query, filter.Category)
	} else {
		rows = market.Filter(rows, filter.Query, market.CategoryAll)
	}

	view := MarketView{
		Rows:        rows,
		Counts:      market.Counts(market.Rows(idx, installed, api.Version), filter.Query),
		Categories:  market.Categories,
		Sources:     market.SourceOptions(cfg.MarketSource),
		Base:        base,
		Source:      cfg.MarketSource,
		SourceLabel: market.Label(cfg.MarketSource),
		FromCache:   res.FromCache,
		UpdatedAt:   res.UpdatedAt,
		Err:         res.Err,
	}
	if stage != nil {
		view.Staged = stage.Entry.ID
	}
	return view
}

// MarketSetSource 切换来源并重新拉取（"auto" = 依次尝试候选表）。
func (a *App) MarketSetSource(source string) MarketView {
	cfg, err := a.st.LoadConfig()
	if err != nil {
		return a.MarketRefresh(MarketFilter{})
	}
	cfg.MarketSource = source
	if err := a.st.SaveConfig(cfg); err != nil {
		return a.MarketRefresh(MarketFilter{})
	}
	a.srv.AddLog("插件市场来源: "+market.Label(source), "info")
	return a.MarketRefresh(MarketFilter{})
}

// MarketAssetURL 给前端一个可直接 <img src> 的图标/截图地址（前端不拼基址）。
func (a *App) MarketAssetURL(path string) string {
	ms := a.marketState()
	ms.mu.Lock()
	base := ms.base
	ms.mu.Unlock()
	if base == "" || path == "" {
		return ""
	}
	return market.Join(base, path)
}

// MarketFetchPackage 下载并校验某个插件的包，返回清单预览（不安装）。
//
// 校验不过直接返回错误：索引里的 sha256 对不上就没必要问用户。
func (a *App) MarketFetchPackage(id string) (store.PluginInfo, error) {
	ms := a.marketState()
	ms.mu.Lock()
	idx, base := ms.index, ms.base
	ms.mu.Unlock()
	if idx == nil {
		return store.PluginInfo{}, fmt.Errorf("还没拿到市场索引，请先刷新")
	}
	var target *market.Entry
	for i := range idx.Plugins {
		if idx.Plugins[i].ID == id {
			target = &idx.Plugins[i]
			break
		}
	}
	if target == nil {
		return store.PluginInfo{}, fmt.Errorf("市场里没有这个插件：%s", id)
	}
	if base == "" {
		return store.PluginInfo{}, fmt.Errorf("还没确定下载来源，请先刷新")
	}
	host := HostVersionForMarket()
	if !market.HostVersionCovers(host, target.MinHydroLink) {
		return store.PluginInfo{}, fmt.Errorf("HydroLink 版本过低：需要 v%s 及以上，当前 v%s", target.MinHydroLink, host)
	}

	dest := filepath.Join(a.st.Dir(), "market-staging", target.ID+"-"+target.Version+".hpk")
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return store.PluginInfo{}, err
	}
	if err := ms.client.Download(base, *target, dest); err != nil {
		return store.PluginInfo{}, err
	}
	m, err := a.st.ReadPluginPackage(dest)
	if err != nil {
		os.Remove(dest)
		return store.PluginInfo{}, err
	}
	ms.mu.Lock()
	ms.stage = &MarketStage{Path: dest, Entry: *target, Preview: PluginInfoFromManifest(m), Base: base}
	ms.mu.Unlock()
	a.srv.AddLog(fmt.Sprintf("已下载插件包待确认: %s v%s", target.ID, target.Version), "info")
	return PluginInfoFromManifest(m), nil
}

// MarketInstallPackage 安装已确认的包（MarketFetchPackage 下载的那一个）。
func (a *App) MarketInstallPackage() (store.PluginInfo, error) {
	ms := a.marketState()
	ms.mu.Lock()
	stage := ms.stage
	ms.stage = nil
	ms.mu.Unlock()
	if stage == nil {
		return store.PluginInfo{}, store.ErrPluginCanceled
	}
	defer os.Remove(stage.Path)
	info, err := a.st.InstallPlugin(stage.Path)
	if err != nil {
		return store.PluginInfo{}, err
	}
	if err := a.srv.LoadPlugin(info.ID); err != nil {
		a.srv.AddLog(fmt.Sprintf("插件已安装但热加载失败: %s (%v)", info.ID, err), "error")
	} else {
		a.srv.AddLog(fmt.Sprintf("已安装插件: %s v%s", info.ID, info.Version), "success")
	}
	// 装完索引里这条的状态变了（已装/可更新），重新算一遍给前端
	return info, nil
}

// MarketDiscardStage 放弃待确认的包（用户点了取消）。
func (a *App) MarketDiscardStage() {
	ms := a.marketState()
	ms.mu.Lock()
	stage := ms.stage
	ms.stage = nil
	ms.mu.Unlock()
	if stage != nil {
		os.Remove(stage.Path)
	}
}

// MarketUpdates 返回已装插件里「市场有新版」的清单（插件页显示升级标记）。
func (a *App) MarketUpdates() []market.Update {
	ms := a.marketState()
	ms.mu.Lock()
	idx := ms.index
	ms.mu.Unlock()
	if idx == nil {
		return nil
	}
	return market.Updates(idx, a.installedVersions(), HostVersionForMarket())
}

// MarketUpdateStage 下载某个更新的包（与 MarketFetchPackage 同一条链路）。
func (a *App) MarketUpdateStage(id string) (store.PluginInfo, error) {
	return a.MarketFetchPackage(id)
}

// MarketUpdateDialog 返回更新弹窗正文（前端直接显示，省得自己拼文案）。
func (a *App) MarketUpdateDialog(id string) string {
	ms := a.marketState()
	ms.mu.Lock()
	idx, base := ms.index, ms.base
	ms.mu.Unlock()
	if idx == nil {
		return ""
	}
	installed := a.installedVersions()
	for _, u := range market.Updates(idx, installed, HostVersionForMarket()) {
		if u.Entry.ID == id {
			return market.UpdateDialogText(u, base, HostVersionForMarket())
		}
	}
	return ""
}

// installedVersions 是已装插件的 id → 版本（市场只比版本）。
func (a *App) installedVersions() map[string]string {
	out := map[string]string{}
	for _, info := range a.st.ListPlugins() {
		out[info.ID] = info.Version
	}
	return out
}

// HostVersionForMarket 是宿主版本（市场判定 min_hydrolink 用）。
func HostVersionForMarket() string {
	if v := store.HostVersion; v != "" && v != "0.0.0" {
		return v
	}
	return api.Version
}
