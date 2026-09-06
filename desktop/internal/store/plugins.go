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
	"strings"
	"time"
)

// PluginManifest mirrors docs/hydroplugin.md §3.1.
type PluginManifest struct {
	ID          string       `json:"id"`
	Name        string       `json:"name"`
	Version     string       `json:"version"`
	Icon        string       `json:"icon,omitempty"`
	Entry       string       `json:"entry"`
	Apps        []PluginItem `json:"apps"`
	Widgets     []PluginItem `json:"widgets"`
	Permissions []string     `json:"permissions"`
}

// PluginItem is an app/widget registration inside a plugin manifest.
type PluginItem struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	RefreshMs int    `json:"refreshMs,omitempty"`
}

// PluginInfo is the list/management entry shown in the desktop HydroPlugin tab.
type PluginInfo struct {
	ID          string       `json:"id"`
	Name        string       `json:"name"`
	Version     string       `json:"version"`
	Entry       string       `json:"entry"`
	Apps        []PluginItem `json:"apps"`
	Widgets     []PluginItem `json:"widgets"`
	Permissions []string     `json:"permissions"`
	InstalledAt string       `json:"installedAt"`
	Dir         string       `json:"dir"`
	Builtin     bool         `json:"builtin"`
	HasWeb      bool         `json:"hasWeb"`
	Error       string       `json:"error,omitempty"`
}

// ErrPluginCanceled is returned when the user cancels an install dialog.
var ErrPluginCanceled = errors.New("canceled")

var pluginIDRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{1,120}$`)

// NewIn creates a new Store rooted at an explicit directory (tests, embedded
// runtimes). Callers are responsible for directory creation.
func NewIn(dir string) *Store {
	return &Store{dir: dir}
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
		info.Version = m.Version
		info.Entry = m.Entry
		info.Apps = orEmpty(m.Apps)
		info.Widgets = orEmpty(m.Widgets)
		info.Permissions = orEmpty(m.Permissions)
		if _, err := os.Stat(filepath.Join(dir, id, "web", "index.html")); err == nil {
			info.HasWeb = true
		}
		if st, err := os.Stat(filepath.Join(dir, id, ".installed_at")); err == nil {
			info.InstalledAt = st.ModTime().Format("2006-01-02 15:04")
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
// follows docs/hydroplugin.md §3: manifest.json + server.js at the package
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
	if m.Entry == "" {
		m.Entry = "server.js"
	}
	if ok, _ := hasEntry(zr.File, prefix, m.Entry); !ok {
		return nil, fmt.Errorf("安装包缺少入口文件 %s", m.Entry)
	}
	if len(orEmpty(m.Apps)) == 0 && len(orEmpty(m.Widgets)) == 0 {
		return nil, fmt.Errorf("插件没有注册任何 App / Widget")
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
	if _, err := os.Stat(target); err == nil {
		return PluginInfo{}, fmt.Errorf("插件已存在（先卸载再安装）: %s", m.ID)
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
	if err := os.WriteFile(filepath.Join(tmp, ".installed_at"), []byte{}, 0o644); err != nil {
		return PluginInfo{}, err
	}
	if err := os.Rename(tmp, target); err != nil {
		return PluginInfo{}, fmt.Errorf("安装失败: %v", err)
	}
	return PluginInfo{
		ID:          m.ID,
		Name:        m.Name,
		Version:     m.Version,
		Entry:       m.Entry,
		Apps:        apps,
		Widgets:     widgets,
		Permissions: orEmpty(m.Permissions),
		InstalledAt: time.Now().Format("2006-01-02 15:04"),
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
