package app

import (
	"archive/zip"
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestPluginHotLoad verifies install/uninstall take effect immediately in the
// running runtime (no server restart): LoadPlugin makes /api/hydro/list and
// dispatch see the plugin; UnloadPlugin removes it again.
func TestPluginHotLoad(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	srv, st, err := NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	h := srv.Handler()

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range map[string]string{
		"manifest.json": `{"id":"com.test.hot","name":"热载测试","author":"测试作者","version":"0.1.0","entry":"server.js","apps":[{"id":"main","name":"主页"}]}`,
		"server.js":     `module.exports = { manifest(){ return {} }, view: function(s,i,r){ return null } }`,
	} {
		w, _ := zw.Create(name)
		_, _ = w.Write([]byte(content))
	}
	_ = zw.Close()
	hpk := filepath.Join(t.TempDir(), "hot.hpk")
	if err := os.WriteFile(hpk, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	info, err := st.InstallPlugin(hpk)
	if err != nil {
		t.Fatalf("install: %v", err)
	}

	has := func(want bool) {
		t.Helper()
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/hydro/list", nil))
		got := strings.Contains(rr.Body.String(), "com.test.hot")
		if got != want {
			t.Fatalf("list contains com.test.hot = %v (want %v): %s", got, want, rr.Body.String())
		}
	}

	has(false) // installed on disk but not registered yet
	if err := srv.LoadPlugin(info.ID); err != nil {
		t.Fatalf("load: %v", err)
	}
	has(true)
	if err := srv.UnloadPlugin(info.ID); err != nil {
		t.Fatalf("unload: %v", err)
	}
	has(false)
}
