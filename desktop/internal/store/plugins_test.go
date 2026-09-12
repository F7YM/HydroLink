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

func TestInstallDuplicate(t *testing.T) {
	s := testStore(t)
	p := buildZip(t, map[string]string{
		"manifest.json": validManifest,
		"server.js":     "x",
	})
	if _, err := s.InstallPlugin(p); err != nil {
		t.Fatal(err)
	}
	if _, err := s.InstallPlugin(p); err == nil || !strings.Contains(err.Error(), "已存在") {
		t.Fatalf("second install err = %v, want already installed", err)
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
