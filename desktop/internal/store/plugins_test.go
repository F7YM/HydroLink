package store

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func testStore(t *testing.T) *Store {
	t.Helper()
	return &Store{dir: t.TempDir()}
}

// buildZip writes a zip archive with the given entries (name -> content).
func buildZip(t *testing.T, entries map[string]string, dirs ...string) string {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, d := range dirs {
		w, err := zw.Create(d)
		if err != nil {
			t.Fatal(err)
		}
		_ = w
	}
	for name, content := range entries {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(t.TempDir(), "plugin.hpk")
	if err := os.WriteFile(p, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

const validManifest = `{
  "id": "com.test.plugin",
  "name": "测试插件",
  "author": "测试作者",
  "version": "0.1.0",
  "apps": [{"id":"main","name":"主页"}],
  "widgets": [{"id":"now","name":"简述","refreshMs":30000}],
  "permissions": ["notify","storage"]
}`

func TestInstallListUninstall(t *testing.T) {
	s := testStore(t)
	p := buildZip(t, map[string]string{
		"manifest.json": validManifest,
		"server.js":     "module.exports = { manifest(){}, view(){ return null } }",
	})
	info, err := s.InstallPlugin(p)
	if err != nil {
		t.Fatalf("install: %v", err)
	}
	if info.ID != "com.test.plugin" || info.Name != "测试插件" {
		t.Fatalf("info = %+v", info)
	}
	if len(info.Permissions) != 2 {
		t.Fatalf("permissions = %+v", info.Permissions)
	}

	list := s.ListPlugins()
	if len(list) != 1 || list[0].ID != "com.test.plugin" {
		t.Fatalf("list = %+v", list)
	}

	if err := s.UninstallPlugin("com.test.plugin"); err != nil {
		t.Fatalf("uninstall: %v", err)
	}
	if got := s.ListPlugins(); len(got) != 0 {
		t.Fatalf("list after uninstall = %+v", got)
	}
}

func TestInstallWrappedDir(t *testing.T) {
	s := testStore(t)
	p := buildZip(t, map[string]string{
		"com.test.wrapped/manifest.json":     validManifest,
		"com.test.wrapped/server.js":         "// entry",
		"com.test.wrapped/assets/model.wasm": "wasm",
	})
	info, err := s.InstallPlugin(p)
	if err != nil {
		t.Fatalf("install (wrapped): %v", err)
	}
	// The install directory follows the manifest id, not the zip wrapper name.
	if info.ID != "com.test.plugin" || info.Dir == "" {
		t.Fatalf("info = %+v", info)
	}
	dir := info.Dir
	if _, err := os.Stat(filepath.Join(dir, "server.js")); err != nil {
		t.Fatalf("entry not extracted at root: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "assets", "model.wasm")); err != nil {
		t.Fatalf("asset not extracted: %v", err)
	}
}

func TestInstallRejects(t *testing.T) {
	cases := []struct {
		name    string
		entries map[string]string
		wantErr string
	}{
		{"no manifest", map[string]string{"server.js": "x"}, "manifest"},
		{"no entry", map[string]string{"manifest.json": validManifest}, "server.js"},
		{"bad id", map[string]string{
			"manifest.json": strings.Replace(validManifest, "com.test.plugin", "../evil", 1),
			"server.js":     "x",
		}, "ID"},
		{"zip slip", map[string]string{
			"manifest.json":  validManifest,
			"server.js":      "x",
			"../../evil.txt": "x",
		}, "不安全路径"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			s := testStore(t)
			p := buildZip(t, c.entries)
			_, err := s.InstallPlugin(p)
			if err == nil || !strings.Contains(err.Error(), c.wantErr) {
				t.Fatalf("err = %v, want contains %q", err, c.wantErr)
			}
		})
	}
}

func TestReinstallUpdateAndDowngrade(t *testing.T) {
	s := testStore(t)
	v := func(ver string) string {
		return strings.Replace(validManifest, `"version": "0.1.0"`, `"version": "`+ver+`"`, 1)
	}
	install := func(ver string) error {
		p := buildZip(t, map[string]string{"manifest.json": v(ver), "server.js": "x"})
		_, err := s.InstallPlugin(p)
		return err
	}

	if err := install("0.1.0"); err != nil {
		t.Fatalf("first install: %v", err)
	}
	// 同版本重装：允许
	if err := install("0.1.0"); err != nil {
		t.Fatalf("reinstall same version should be allowed: %v", err)
	}
	// 更高版本：允许（更新）
	if err := install("0.2.0"); err != nil {
		t.Fatalf("update to higher version should be allowed: %v", err)
	}
	if got := s.ListPlugins(); len(got) != 1 || got[0].Version != "0.2.0" {
		t.Fatalf("version not updated: %+v", got)
	}
	// 更低版本：拒绝
	err := install("0.1.9")
	if err == nil || !strings.Contains(err.Error(), "不允许降级") {
		t.Fatalf("downgrade err = %v, want 不允许降级", err)
	}
	// 拒绝后原版本仍在
	if got := s.ListPlugins(); len(got) != 1 || got[0].Version != "0.2.0" {
		t.Fatalf("existing install damaged by rejected downgrade: %+v", got)
	}
}

func TestCompareVersions(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"0.1.0", "0.1.0", 0},
		{"0.2.0", "0.1.9", 1},
		{"0.1.9", "0.2.0", -1},
		{"1.0", "1.0.0", 0},
		{"0.10.0", "0.9.0", 1},
		{"0.2.0-beta", "0.2.0", -1},
	}
	for _, c := range cases {
		if got := CompareVersions(c.a, c.b); got != c.want {
			t.Fatalf("CompareVersions(%q,%q) = %d, want %d", c.a, c.b, got, c.want)
		}
	}
}

func TestInstallBadExtension(t *testing.T) {
	s := testStore(t)
	p := filepath.Join(t.TempDir(), "plugin.txt")
	_ = os.WriteFile(p, []byte("x"), 0o644)
	if _, err := s.InstallPlugin(p); err == nil {
		t.Fatal("expected extension error")
	}
}

func TestWasmRuntimePackage(t *testing.T) {
	wasmManifest := `{
  "id": "com.test.wasm",
  "name": "WASM 插件",
  "author": "测试作者",
  "version": "0.1.0",
  "runtime": "wasm",
  "apps": [{"id":"main","name":"主页"}],
  "permissions": ["system"]
}`
	s := testStore(t)
	p := buildZip(t, map[string]string{
		"manifest.json": wasmManifest,
		"plugin.wasm":   "\x00asm\x01\x00\x00\x00", // minimal wasm magic
	})
	info, err := s.InstallPlugin(p)
	if err != nil {
		t.Fatalf("install wasm plugin: %v", err)
	}
	if info.Runtime != "wasm" || info.Entry != "plugin.wasm" {
		t.Fatalf("info = %+v (want runtime=wasm entry=plugin.wasm)", info)
	}
	if got := s.ListPlugins(); len(got) != 1 || got[0].Runtime != "wasm" {
		t.Fatalf("list = %+v", got)
	}

	// runtime=wasm with a non-wasm entry is rejected
	bad := buildZip(t, map[string]string{
		"manifest.json": strings.Replace(wasmManifest, `"permissions"`, `"entry": "server.js", "permissions"`, 1),
		"server.js":     "x",
		"plugin.wasm":   "\x00asm\x01\x00\x00\x00",
	})
	if _, err := s.InstallPlugin(bad); err == nil || !strings.Contains(err.Error(), ".wasm") {
		t.Fatalf("err = %v, want .wasm entry error", err)
	}

	// missing wasm entry is rejected
	missing := buildZip(t, map[string]string{"manifest.json": wasmManifest})
	if _, err := s.InstallPlugin(missing); err == nil || !strings.Contains(err.Error(), "plugin.wasm") {
		t.Fatalf("err = %v, want missing entry error", err)
	}
}

// ---- 清单新字段：author 必填、url 可选、minHydroLink 卡宿主版本 ----

func TestInstallRequiresAuthor(t *testing.T) {
	s := testStore(t)
	noAuthor := strings.Replace(validManifest, `"author": "测试作者",`, "", 1)
	p := buildZip(t, map[string]string{"manifest.json": noAuthor, "server.js": "x"})
	if _, err := s.InstallPlugin(p); err == nil || !strings.Contains(err.Error(), "author") {
		t.Fatalf("缺 author 应当拒绝安装，实际: %v", err)
	}
}

func TestInstallRejectsNewerMinHydroLink(t *testing.T) {
	old := HostVersion
	HostVersion = "1.1.0"
	defer func() { HostVersion = old }()

	mk := func(min string) string {
		return strings.Replace(validManifest, `"version": "0.1.0",`,
			`"version": "0.1.0", "minHydroLink": "`+min+`",`, 1)
	}
	// 要求比宿主新 -> 拒绝
	s := testStore(t)
	p := buildZip(t, map[string]string{"manifest.json": mk("1.2.0"), "server.js": "x"})
	if _, err := s.InstallPlugin(p); err == nil || !strings.Contains(err.Error(), "minHydroLink") &&
		!strings.Contains(err.Error(), "HydroLink ≥") {
		t.Fatalf("minHydroLink 高于宿主应当拒绝，实际: %v", err)
	}
	// 与宿主相同 / 更低 -> 允许
	for _, min := range []string{"1.1.0", "1.0.0"} {
		s := testStore(t)
		p := buildZip(t, map[string]string{"manifest.json": mk(min), "server.js": "x"})
		if _, err := s.InstallPlugin(p); err != nil {
			t.Fatalf("minHydroLink=%s 应当允许安装: %v", min, err)
		}
	}
}

func TestPluginInfoCarriesAuthorAndURL(t *testing.T) {
	s := testStore(t)
	m := strings.Replace(validManifest, `"version": "0.1.0",`,
		`"version": "0.1.0", "url": "https://example.com/p",`, 1)
	p := buildZip(t, map[string]string{"manifest.json": m, "server.js": "x"})
	if _, err := s.InstallPlugin(p); err != nil {
		t.Fatal(err)
	}
	list := s.ListPlugins()
	if len(list) != 1 {
		t.Fatalf("插件数: %d", len(list))
	}
	if list[0].Author != "测试作者" || list[0].URL != "https://example.com/p" {
		t.Fatalf("作者/项目地址没传出来: %+v", list[0])
	}
}
