package store

import (
	"archive/zip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// HostVersion 是宿主自己的版本（由装配处从 api.Version 注入），用来校验清单里的
// minHydroLink —— store 不能反过来 import api。
var HostVersion = "0.0.0"

// PluginManifest mirrors the manifest reference in the HydroKit docs (GitBook).
type PluginManifest struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// Author 必填：插件必须自报作者。注意它**只是展示信息**，没有签名，
	// 平台无法验证真伪（可信作者要等签名那一层）。
	Author  string `json:"author"`
	Version string `json:"version"`
	// URL 可选：项目地址。
	URL string `json:"url,omitempty"`
	// MinHydroLink 可选：要求的最低 HydroLink 版本，宿主版本低于它就不给装。
	MinHydroLink string `json:"minHydroLink,omitempty"`
	Icon         string `json:"icon,omitempty"`
	// Runtime is "js" (default, server.js in a WebWorker) or "wasm"
	// (plugin.wasm loaded by the WorkerBundle's HydroKit WASM host, ABI v1).
	Runtime     string       `json:"runtime,omitempty"`
	Entry       string       `json:"entry"`
	Apps        []PluginItem `json:"apps"`
	Widgets     []PluginItem `json:"widgets"`
	Permissions []string     `json:"permissions"`
}

// PluginItem is an app/widget registration inside a plugin manifest.
type PluginItem struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// Icon 是包内图标路径（如 "assets/icon.png"），列表/市场展示用。
	// 之前这个字段没人读，两端表现不一致（安卓侧读），这里补上。
	Icon      string `json:"icon,omitempty"`
	RefreshMs int    `json:"refreshMs,omitempty"`
}

// PluginInfo is the list/management entry shown in the desktop HydroPlugin tab.
type PluginInfo struct {
	ID           string       `json:"id"`
	Name         string       `json:"name"`
	Author       string       `json:"author"`
	Version      string       `json:"version"`
	URL          string       `json:"url,omitempty"`
	MinHydroLink string       `json:"minHydroLink,omitempty"`
	Entry        string       `json:"entry"`
	Runtime      string       `json:"runtime"`
	Apps         []PluginItem `json:"apps"`
	Widgets      []PluginItem `json:"widgets"`
	Permissions  []string     `json:"permissions"`
	Dir          string       `json:"dir"`
	Builtin      bool         `json:"builtin"`
	HasWeb       bool         `json:"hasWeb"`
	Error        string       `json:"error,omitempty"`
	// Icon 是包内图标路径（manifest 顶层 icon，或第一个 app 的 icon）。
	Icon string `json:"icon,omitempty"`
}

// CompareVersions orders dotted versions (numeric segments compared as
// numbers, non-numeric fall back to string compare). Returns -1/0/1.
func CompareVersions(a, b string) int {
	as := strings.Split(strings.TrimSpace(a), ".")
	bs := strings.Split(strings.TrimSpace(b), ".")
	n := len(as)
	if len(bs) > n {
		n = len(bs)
	}
	for i := 0; i < n; i++ {
		// 缺失段按 0 处理（"1.0" == "1.0.0"）
		av, bv := "0", "0"
		if i < len(as) {
			av = as[i]
		}
		if i < len(bs) {
			bv = bs[i]
		}
		if c := cmpSegment(av, bv); c != 0 {
			return c
		}
	}
	return 0
}

// cmpSegment compares one version segment: numeric when possible; otherwise the
// numeric prefix decides and a segment carrying a suffix ("0-beta") sorts below
// the bare number ("0") — i.e. prereleases are older.
func cmpSegment(av, bv string) int {
	an, aerr := strconv.Atoi(av)
	bn, berr := strconv.Atoi(bv)
	if aerr == nil && berr == nil {
		switch {
		case an < bn:
			return -1
		case an > bn:
			return 1
		}
		return 0
	}
	ap, aok := leadingInt(av)
	bp, bok := leadingInt(bv)
	if aok && bok {
		switch {
		case ap < bp:
			return -1
		case ap > bp:
			return 1
		}
		aSuffix := len(av) > len(strconv.Itoa(ap))
		bSuffix := len(bv) > len(strconv.Itoa(bp))
		if aSuffix != bSuffix {
			if aSuffix {
				return -1
			}
			return 1
		}
	}
	if av < bv {
		return -1
	}
	if av > bv {
		return 1
	}
	return 0
}

// leadingInt returns the leading integer of a segment ("12beta" -> 12, true).
func leadingInt(s string) (int, bool) {
	i := 0
	for i < len(s) && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	if i == 0 {
		return 0, false
	}
	n, err := strconv.Atoi(s[:i])
	return n, err == nil
}

// ErrPluginCanceled is returned when the user cancels an install dialog.
var ErrPluginCanceled = errors.New("canceled")

// Plugin runtimes.
const (
	RuntimeJS   = "js"   // server.js executed by the HydroKit worker
	RuntimeWasm = "wasm" // plugin.wasm executed by the HydroKit WASM host (ABI v1)
)

func normalizeRuntime(rt string) string {
	switch strings.ToLower(strings.TrimSpace(rt)) {
	case "", RuntimeJS:
		return RuntimeJS
	case RuntimeWasm:
		return RuntimeWasm
	default:
		return strings.ToLower(strings.TrimSpace(rt))
	}
}

var pluginIDRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{1,120}$`)

// NewIn creates a new Store rooted at an explicit directory (tests, embedded
// runtimes). Callers are responsible for directory creation.
func NewIn(dir string) *Store {
	return &Store{
		dir:          dir,
		trustedFile:  filepath.Join(dir, "trusted_devices.json"),
		commandsFile: filepath.Join(dir, "commands.json"),
		configFile:   filepath.Join(dir, "config.json"),
		rejectedFile: filepath.Join(dir, "rejected_devices.json"),
		pendingFile:  filepath.Join(dir, "pending_pair.json"),
	}
}

// PluginsDir returns the directory where plugin packages are installed
// (one subdirectory per plugin id).
func (s *Store) PluginsDir() string {
	return filepath.Join(s.dir, "plugins")
}

// ListPlugins scans the plugins directory and reports every installed plugin.
// Directories whose manifest.json cannot be read are listed with Error set.
func (s *Store) ListPlugins() []PluginInfo {
	dir := s.PluginsDir()
	entries, err := os.ReadDir(dir)
	if err != nil {
		return []PluginInfo{}
	}
	var out []PluginInfo
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		id := e.Name()
		info := PluginInfo{ID: id, Dir: filepath.Join(dir, id)}
		raw, err := os.ReadFile(filepath.Join(dir, id, "manifest.json"))
		if err != nil {
			info.Error = "manifest.json: " + err.Error()
			out = append(out, info)
			continue
		}
		var m PluginManifest
		if err := json.Unmarshal(raw, &m); err != nil {
			info.Error = "manifest.json: " + err.Error()
			out = append(out, info)
			continue
		}
		info.Name = m.Name
		info.Author = m.Author
		info.Version = m.Version
		info.URL = m.URL
		info.MinHydroLink = m.MinHydroLink
		info.Entry = m.Entry
		info.Runtime = normalizeRuntime(m.Runtime)
		info.Apps = orEmpty(m.Apps)
		info.Widgets = orEmpty(m.Widgets)
		info.Permissions = orEmpty(m.Permissions)
		if _, err := os.Stat(filepath.Join(dir, id, "web", "index.html")); err == nil {
			info.HasWeb = true
		}
		if info.Name == "" {
			info.Name = id
		}
		out = append(out, info)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

func orEmpty[T any](s []T) []T {
	if s == nil {
		return []T{}
	}
	return s
}

// ReadPluginPackage validates an .hpk/.zip package and returns its manifest
// WITHOUT installing (used by the install-confirmation UI). The layout
// follows the manifest reference in the HydroKit docs: manifest.json + server.js at the package
// root (a single wrapping top-level directory is tolerated and stripped).
func (s *Store) ReadPluginPackage(zipPath string) (*PluginManifest, error) {
	if zipPath == "" {
		return nil, ErrPluginCanceled
	}
	ext := strings.ToLower(filepath.Ext(zipPath))
	if ext != ".hpk" && ext != ".zip" {
		return nil, fmt.Errorf("只支持 .hpk / .zip 安装包")
	}
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return nil, fmt.Errorf("无法打开安装包: %v", err)
	}
	defer zr.Close()

	prefix := commonPrefix(zr.File)
	if ok, _ := hasEntry(zr.File, prefix, "manifest.json"); !ok {
		return nil, fmt.Errorf("安装包缺少 manifest.json")
	}
	raw, err := readEntry(zr.File, prefix, "manifest.json")
	if err != nil {
		return nil, fmt.Errorf("读取 manifest.json 失败: %v", err)
	}
	var m PluginManifest
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("manifest.json 格式错误: %v", err)
	}
	if !pluginIDRe.MatchString(m.ID) {
		return nil, fmt.Errorf("无效的包 ID: %q", m.ID)
	}
	m.Runtime = normalizeRuntime(m.Runtime)
	if m.Entry == "" {
		if m.Runtime == RuntimeWasm {
			m.Entry = "plugin.wasm"
		} else {
			m.Entry = "server.js"
		}
	}
	if m.Runtime == RuntimeWasm && !strings.HasSuffix(strings.ToLower(m.Entry), ".wasm") {
		return nil, fmt.Errorf("runtime=wasm 的入口必须是 .wasm 文件: %s", m.Entry)
	}
	if ok, _ := hasEntry(zr.File, prefix, m.Entry); !ok {
		return nil, fmt.Errorf("安装包缺少入口文件 %s", m.Entry)
	}
	if len(orEmpty(m.Apps)) == 0 && len(orEmpty(m.Widgets)) == 0 {
		return nil, fmt.Errorf("插件没有注册任何 App / Widget")
	}
	if strings.TrimSpace(m.Author) == "" {
		return nil, fmt.Errorf("清单缺少 author（作者必填）")
	}
	if v := strings.TrimSpace(m.MinHydroLink); v != "" && CompareVersions(HostVersion, v) < 0 {
		return nil, fmt.Errorf("该插件需要 HydroLink ≥ %s，当前 %s", v, HostVersion)
	}
	return &m, nil
}

// InstallPlugin validates an .hpk/.zip package and installs it under
// plugins/{id}.
func (s *Store) InstallPlugin(zipPath string) (PluginInfo, error) {
	m, err := s.ReadPluginPackage(zipPath)
	if err != nil {
		return PluginInfo{}, err
	}
	apps := orEmpty(m.Apps)
	widgets := orEmpty(m.Widgets)

	target := filepath.Join(s.PluginsDir(), m.ID)
	// 已安装：允许同版本重装、更高版本更新；禁止降级。
	if raw, err := os.ReadFile(filepath.Join(target, "manifest.json")); err == nil {
		var existing PluginManifest
		if json.Unmarshal(raw, &existing) == nil && existing.Version != "" {
			switch CompareVersions(m.Version, existing.Version) {
			case -1:
				return PluginInfo{}, fmt.Errorf("不允许降级：已安装 v%s，安装包为 v%s", existing.Version, m.Version)
			}
		}
	}
	if err := os.MkdirAll(s.PluginsDir(), 0o755); err != nil {
		return PluginInfo{}, err
	}
	// Reopen the archive for extraction (ReadPluginPackage closed its reader).
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return PluginInfo{}, fmt.Errorf("无法打开安装包: %v", err)
	}
	defer zr.Close()
	prefix := commonPrefix(zr.File)

	tmp, err := os.MkdirTemp(s.PluginsDir(), ".tmp-"+m.ID+"-")
	if err != nil {
		return PluginInfo{}, err
	}
	defer os.RemoveAll(tmp)

	if err := extractZip(zr.File, prefix, tmp); err != nil {
		return PluginInfo{}, err
	}
	if _, err := os.Stat(filepath.Join(tmp, "manifest.json")); err != nil {
		return PluginInfo{}, fmt.Errorf("解压后缺少 manifest.json")
	}
	if _, err := os.Stat(filepath.Join(tmp, m.Entry)); err != nil {
		return PluginInfo{}, fmt.Errorf("解压后缺少入口文件 %s", m.Entry)
	}
	// 更新/重装：先移除旧目录再落位（插件存储在 plugins-storage 下，不受影响）
	if _, err := os.Stat(target); err == nil {
		if err := os.RemoveAll(target); err != nil {
			return PluginInfo{}, fmt.Errorf("替换旧版本失败: %v", err)
		}
	}
	if err := os.Rename(tmp, target); err != nil {
		return PluginInfo{}, fmt.Errorf("安装失败: %v", err)
	}
	return PluginInfo{
		ID:          m.ID,
		Name:        m.Name,
		Version:     m.Version,
		Entry:       m.Entry,
		Runtime:     m.Runtime,
		Apps:        apps,
		Widgets:     widgets,
		Permissions: orEmpty(m.Permissions),
		Dir:         target,
	}, nil
}

// UninstallPlugin removes an installed plugin by id.
func (s *Store) UninstallPlugin(id string) error {
	if !pluginIDRe.MatchString(id) {
		return fmt.Errorf("无效的插件 ID: %q", id)
	}
	target := filepath.Join(s.PluginsDir(), id)
	// Guard against path traversal / removing the plugins dir itself.
	if filepath.Dir(target) != s.PluginsDir() {
		return fmt.Errorf("无效的插件路径")
	}
	if _, err := os.Stat(target); err != nil {
		return fmt.Errorf("插件不存在: %s", id)
	}
	return os.RemoveAll(target)
}

// GetPluginManifest reads and parses plugins/{id}/manifest.json.
func (s *Store) GetPluginManifest(id string) (*PluginManifest, error) {
	raw, err := os.ReadFile(filepath.Join(s.PluginsDir(), id, "manifest.json"))
	if err != nil {
		return nil, err
	}
	var m PluginManifest
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("manifest.json: %w", err)
	}
	if m.ID == "" {
		m.ID = id
	}
	return &m, nil
}

// ---- plugin key-value storage (server scope) ----

// PluginKVFile returns the storage file path for a plugin's server-scope KV.
func (s *Store) PluginKVFile(id string) string {
	return filepath.Join(s.dir, "plugins-storage", id+".json")
}

// LoadPluginKV loads the plugin's server-scope key-value map (missing file
// returns an empty map).
func (s *Store) LoadPluginKV(id string) (map[string]any, error) {
	raw, err := os.ReadFile(s.PluginKVFile(id))
	if err != nil {
		if os.IsNotExist(err) {
			return map[string]any{}, nil
		}
		return nil, err
	}
	kv := map[string]any{}
	if err := json.Unmarshal(raw, &kv); err != nil {
		return nil, err
	}
	return kv, nil
}

// SavePluginKV persists the plugin's server-scope key-value map.
func (s *Store) SavePluginKV(id string, kv map[string]any) error {
	if !pluginIDRe.MatchString(id) {
		return fmt.Errorf("无效的插件 ID: %q", id)
	}
	if err := os.MkdirAll(filepath.Dir(s.PluginKVFile(id)), 0o755); err != nil {
		return err
	}
	data, err := json.Marshal(kv)
	if err != nil {
		return err
	}
	tmp := s.PluginKVFile(id) + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, s.PluginKVFile(id))
}

// ReadPluginFile reads a file from inside a plugin package, refusing paths
// that escape the plugin directory.
func (s *Store) ReadPluginFile(id, rel string) ([]byte, error) {
	clean := filepath.Clean(filepath.FromSlash(rel))
	if clean == "." || filepath.IsAbs(clean) ||
		strings.HasPrefix(clean, ".."+string(filepath.Separator)) || clean == ".." {
		return nil, fmt.Errorf("非法路径: %s", rel)
	}
	root := filepath.Join(s.PluginsDir(), id)
	full := filepath.Join(root, clean)
	if filepath.Dir(full) != root &&
		!strings.HasPrefix(filepath.Dir(full), root+string(filepath.Separator)) {
		return nil, fmt.Errorf("非法路径: %s", rel)
	}
	return os.ReadFile(full)
}

// ---- zip helpers ----

// commonPrefix strips the single top-level directory name when every entry
// shares it (packs of the form `com.demo.weather/{...}`), returning "" if the
// archive is flat.
func commonPrefix(files []*zip.File) string {
	prefix := ""
	seen := false
	for _, f := range files {
		name := strings.TrimSuffix(f.Name, "/")
		if name == "" || strings.HasPrefix(name, "__MACOSX/") || strings.HasPrefix(name, ".DS_Store") {
			continue
		}
		if strings.HasSuffix(f.Name, "/") {
			continue // directory entries don't constrain the layout
		}
		part, _, ok := strings.Cut(name, "/")
		if !ok {
			return "" // a file at the archive root -> flat layout
		}
		if !seen {
			prefix = part
			seen = true
			continue
		}
		if part != prefix {
			return ""
		}
	}
	return prefix
}

func hasEntry(files []*zip.File, prefix, name string) (bool, error) {
	_, err := readEntry(files, prefix, name)
	if err == nil {
		return true, nil
	}
	if os.IsNotExist(err) {
		return false, nil
	}
	return false, err
}

func readEntry(files []*zip.File, prefix, name string) ([]byte, error) {
	for _, f := range files {
		if strings.TrimPrefix(f.Name, prefix+"/") != name || f.FileInfo().IsDir() {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		defer rc.Close()
		return io.ReadAll(rc)
	}
	return nil, os.ErrNotExist
}

func extractZip(files []*zip.File, prefix, dest string) error {
	for _, f := range files {
		rel := strings.TrimPrefix(f.Name, prefix+"/")
		if rel == "" || strings.HasPrefix(rel, "__MACOSX/") || strings.HasPrefix(rel, ".DS_Store") {
			continue
		}
		clean := filepath.Clean(rel)
		if clean == "." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) || clean == ".." || filepath.IsAbs(clean) {
			return fmt.Errorf("安装包包含不安全路径: %s", f.Name)
		}
		target := filepath.Join(dest, clean)
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
		if err != nil {
			rc.Close()
			return err
		}
		_, err = io.Copy(out, rc)
		rc.Close()
		out.Close()
		if err != nil {
			return err
		}
	}
	return nil
}
