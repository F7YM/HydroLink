// Package gui provides the Wails-bound desktop application: status, pairing,
// commands, settings and log views over the shared API server.
package gui

import (
	"context"
	"fmt"

	"github.com/gen2brain/beeep"
	"github.com/wailsapp/wails/v2/pkg/runtime"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/store"
	"hydrolink-desktop/internal/tray"
)

// App is bound to the Wails frontend (window.go.gui.App.*).
type App struct {
	ctx  context.Context
	srv  *api.Server
	st   *store.Store
	port int

	trayEnabled bool
	trayIcon    []byte
	tray        *tray.Tray
	trayErr     string
}

// NewApp creates the desktop app bindings. trayIcon is the PNG used for the
// system tray pixmap (usually frontend/dist/icon.png).
func NewApp(srv *api.Server, st *store.Store, port int, trayIcon []byte) *App {
	a := &App{srv: srv, st: st, port: port, trayIcon: trayIcon}
	cfg, _ := st.LoadConfig()
	a.trayEnabled = trayEnabledFrom(cfg)
	return a
}

// trayEnabledFrom reads the persisted preference (missing key = enabled).
func trayEnabledFrom(cfg store.Config) bool {
	return cfg.TrayEnabled == nil || *cfg.TrayEnabled
}

// TrayRunning reports whether the tray icon is currently registered.
func (a *App) TrayRunning() bool { return a.tray.Running() }

// applyTray starts or stops the tray icon to match the preference. A failure
// to start is recorded (and returned) so Settings can tell the user why.
func (a *App) applyTray(enabled bool) error {
	a.trayEnabled = enabled
	if !enabled {
		if a.tray != nil {
			a.tray.Stop()
			a.tray = nil
			a.srv.AddLog("托盘图标已关闭", "info")
		}
		a.trayErr = ""
		return nil
	}
	if a.tray.Running() {
		return nil
	}
	t, err := tray.Start(tray.Options{
		IconPNG:    a.trayIcon,
		Title:      "HydroLink",
		Subtitle:   "桌面服务端",
		Logf:       func(msg string, _ ...any) { a.srv.AddLog(msg, "info") },
		OnActivate: a.ShowWindow,
		OnQuit:     a.Quit,
	})
	if err != nil {
		a.trayErr = err.Error()
		a.srv.AddLog("托盘图标启用失败: "+err.Error(), "warning")
		return err
	}
	a.tray = t
	a.trayErr = ""
	a.srv.AddLog("托盘图标已启用（关闭窗口将最小化到托盘）", "success")
	return nil
}

// ShowWindow brings the main window back from the tray.
func (a *App) ShowWindow() {
	if a.ctx == nil {
		return
	}
	runtime.WindowShow(a.ctx)
	runtime.WindowUnminimise(a.ctx)
}

// Quit exits the app (tray menu 退出).
func (a *App) Quit() {
	if a.ctx != nil {
		runtime.Quit(a.ctx)
	}
}

// OnBeforeClose hides the window instead of quitting while the tray icon is
// active, so the HTTP server keeps serving the watch.
func (a *App) OnBeforeClose(ctx context.Context) bool {
	if !a.tray.Running() {
		return false
	}
	runtime.WindowHide(ctx)
	a.srv.AddLog("窗口已最小化到托盘（服务继续运行）", "info")
	return true
}

// Startup is called by Wails when the window is ready.
func (a *App) Startup(ctx context.Context) {
	a.ctx = ctx
	a.srv.AddLog("桌面端已启动", "success")
	// Popup + system notification on every new pairing request.
	a.srv.SetPairCallback(a.onPairRequest)
	// Plugin workers live in this webview; when a request arrives for an
	// offline JS plugin, ask the frontend to spawn its worker.
	if b := a.srv.WorkerBridge(); b != nil {
		b.SetOnNeedWake(func(pkg string) {
			a.srv.AddLog("hydrokit: 唤醒插件 worker: "+pkg, "info")
			runtime.EventsEmit(ctx, "plugins:wake", pkg)
		})
	}
	// System tray (settings toggle; failure is non-fatal and reported in 设置).
	_ = a.applyTray(a.trayEnabled)
}

// onPairRequest fires whenever the API server records a new pending pair.
func (a *App) onPairRequest(deviceID, deviceName string) {
	name := deviceName
	if name == "" {
		name = "Unknown"
	}
	// System notification (works even when the window is not focused).
	_ = beeep.Notify("HydroLink 配对请求", name+" 请求连接", "")
	// Push to the frontend so it can raise a modal immediately.
	if a.ctx != nil {
		runtime.EventsEmit(a.ctx, "pair:request", map[string]string{
			"deviceId":   deviceID,
			"deviceName": deviceName,
		})
	}
}

// Shutdown is called by Wails on exit; stops the HTTP server and the tray.
func (a *App) Shutdown(ctx context.Context) {
	if a.tray != nil {
		a.tray.Stop()
		a.tray = nil
	}
	a.srv.Stop()
}

// Status is the snapshot shown on the status tab.
type Status struct {
	Version        string                `json:"version"`
	APILevel       int                   `json:"apiLevel"`
	Port           int                   `json:"port"`
	IP             string                `json:"ip"`
	DataDir        string                `json:"dataDir"`
	AcceptAll      bool                  `json:"acceptAll"`
	EncryptionKey  string                `json:"encryptionKey"`
	TrustedCount   int                   `json:"trustedCount"`
	TrustedDevices []store.TrustedDevice `json:"trustedDevices"`
}

// GetStatus returns the current server status snapshot.
func (a *App) GetStatus() Status {
	cfg, _ := a.st.LoadConfig()
	devices, _ := a.st.LoadTrustedDevices()
	return Status{
		Version:        api.Version,
		APILevel:       api.APILevel,
		Port:           a.port,
		IP:             api.LocalIP(),
		DataDir:        a.st.Dir(),
		AcceptAll:      cfg.AcceptAll,
		EncryptionKey:  cfg.EncryptionKey,
		TrustedCount:   len(devices),
		TrustedDevices: devices,
	}
}

// GetLogs returns the server log buffer.
func (a *App) GetLogs() []api.LogEntry {
	return a.srv.Logs()
}

// GetPendingPairs returns the pairing requests waiting for confirmation.
func (a *App) GetPendingPairs() []store.PairRequest {
	pairs, _ := a.st.LoadPendingPairs()
	return pairs
}

// RespondPair accepts or rejects a pending pairing request.
func (a *App) RespondPair(deviceID, action, deviceName string) error {
	if action != "accept" && action != "reject" {
		return fmt.Errorf("invalid action")
	}
	_ = a.st.RemovePendingPair(deviceID)
	if action == "accept" {
		if err := a.st.AddTrustedDevice(deviceID, deviceName); err != nil {
			return err
		}
		a.srv.AddLog("已信任设备: "+shortID(deviceID), "success")
	} else {
		if err := a.st.AddRejected(deviceID); err != nil {
			return err
		}
		a.srv.AddLog("拒绝设备: "+shortID(deviceID), "error")
	}
	return nil
}

// DeleteTrustedDevice removes a trusted device.
func (a *App) DeleteTrustedDevice(deviceID string) error {
	a.srv.AddLog("已删除设备: "+shortID(deviceID), "info")
	return a.st.RemoveTrustedDevice(deviceID)
}

// GetCommands returns the quick commands.
func (a *App) GetCommands() []store.Command {
	commands, _ := a.st.LoadCommands()
	return commands
}

// AddCommand appends a quick command.
func (a *App) AddCommand(name, command string) error {
	return a.st.AddCommand(name, command)
}

// RemoveCommand deletes a quick command by index.
func (a *App) RemoveCommand(index int) error {
	return a.st.RemoveCommand(index)
}

// ---- HydroPlugins ----

// GetPlugins returns every installed plugin.
func (a *App) GetPlugins() []store.PluginInfo {
	return a.st.ListPlugins()
}

// PickPluginFile opens a file picker for an .hpk/.zip package and returns the
// chosen path ("" if cancelled). The UI previews it (PreviewPlugin) and asks
// for confirmation before InstallPlugin runs.
func (a *App) PickPluginFile() (string, error) {
	if a.ctx == nil {
		return "", fmt.Errorf("窗口尚未就绪")
	}
	return runtime.OpenFileDialog(a.ctx, runtime.OpenDialogOptions{
		Title: "选择 HydroPlugin 安装包",
		Filters: []runtime.FileFilter{{
			DisplayName: "HydroPlugin 安装包",
			Pattern:     "*.hpk;*.zip",
		}},
	})
}

// PreviewPlugin reads a package's manifest without installing anything.
func (a *App) PreviewPlugin(path string) (store.PluginInfo, error) {
	if path == "" {
		return store.PluginInfo{}, store.ErrPluginCanceled
	}
	m, err := a.st.ReadPluginPackage(path)
	if err != nil {
		return store.PluginInfo{}, err
	}
	return PluginInfoFromManifest(m), nil
}

// PluginInfoFromManifest builds display info from a package manifest.
func PluginInfoFromManifest(m *store.PluginManifest) store.PluginInfo {
	return store.PluginInfo{
		ID:          m.ID,
		Name:        m.Name,
		Version:     m.Version,
		Entry:       m.Entry,
		Apps:        m.Apps,
		Widgets:     m.Widgets,
		Permissions: m.Permissions,
	}
}

// InstallPlugin installs the already-confirmed package path.
func (a *App) InstallPlugin(path string) (store.PluginInfo, error) {
	if path == "" {
		return store.PluginInfo{}, store.ErrPluginCanceled
	}
	info, err := a.st.InstallPlugin(path)
	if err != nil {
		return store.PluginInfo{}, err
	}
	if err := a.srv.LoadPlugin(info.ID); err != nil {
		a.srv.AddLog(fmt.Sprintf("插件已安装但热加载失败: %s (%v)", info.ID, err), "error")
	} else {
		a.srv.AddLog(fmt.Sprintf("已安装插件: %s v%s", info.ID, info.Version), "success")
	}
	return info, nil
}

// UninstallPlugin removes an installed plugin package.
func (a *App) UninstallPlugin(id string) error {
	if err := a.st.UninstallPlugin(id); err != nil {
		return err
	}
	_ = a.srv.UnloadPlugin(id)
	a.srv.AddLog("已卸载插件: "+id, "info")
	return nil
}

// GetPluginBundle returns the self-contained WebWorker script (bootstrap +
// the plugin's server.js) for a package, ready to run via Blob Worker.
// base must be the page's own origin (same-origin with the Wails asset
// server, which proxies /api/* to the in-process API server) — absolute
// same-origin URLs avoid both cross-origin CORS handling and blob-worker
// relative-URL quirks.
func (a *App) GetPluginBundle(id, base string) (string, error) {
	m, err := a.st.GetPluginManifest(id)
	if err != nil {
		return "", err
	}
	entry := m.Entry
	if entry == "" {
		entry = "server.js"
	}
	// WASM plugins: the worker gets a generic host bundle and fetches
	// plugin.wasm itself through the package-asset endpoint.
	if m.Runtime == store.RuntimeWasm {
		return string(hydrokit.WasmBundle(id, entry, base)), nil
	}
	code, err := a.st.ReadPluginFile(id, entry)
	if err != nil {
		return "", fmt.Errorf("读取入口 %s 失败: %v", entry, err)
	}
	return string(hydrokit.WorkerBundle(id, string(code), base)), nil
}

// PluginBridgeLog forwards a frontend worker-runtime message into the server
// log (so worker bootstrap failures are visible in the terminal).
func (a *App) PluginBridgeLog(text string) {
	if text == "" {
		return
	}
	a.srv.AddLog("hydrokit(frontend): "+text, "warning")
}

// Settings holds the editable server settings.
type Settings struct {
	Port      int  `json:"port"`
	AcceptAll bool `json:"acceptAll"`
	// TrayEnabled 是托盘开关；TrayError 非空表示启用失败的原因。
	TrayEnabled bool   `json:"trayEnabled"`
	TrayError   string `json:"trayError"`
}

// GetSettings returns the current settings.
func (a *App) GetSettings() Settings {
	cfg, _ := a.st.LoadConfig()
	return Settings{
		Port:        a.port,
		AcceptAll:   cfg.AcceptAll,
		TrayEnabled: trayEnabledFrom(cfg),
		TrayError:   a.trayErr,
	}
}

// SaveSettings persists settings; changing the port restarts the HTTP server
// and the tray switch is applied immediately (no restart needed).
func (a *App) SaveSettings(port int, acceptAll, trayEnabled bool) error {
	if port < 1 || port > 65535 {
		return fmt.Errorf("端口无效")
	}
	cfg, _ := a.st.LoadConfig()
	cfg.AcceptAll = acceptAll
	cfg.LastPort = port
	cfg.TrayEnabled = &trayEnabled
	if err := a.st.SaveConfig(cfg); err != nil {
		return err
	}
	trayErr := a.applyTray(trayEnabled)
	if port != a.port {
		a.srv.Stop()
		addr := fmt.Sprintf("0.0.0.0:%d", port)
		if err := a.srv.Start(addr); err != nil {
			// fall back to the previous port
			_ = a.srv.Start(fmt.Sprintf("0.0.0.0:%d", a.port))
			return fmt.Errorf("端口 %d 启动失败: %v", port, err)
		}
		a.port = port
		a.srv.AddLog(fmt.Sprintf("服务已重启，监听端口 %d", port), "success")
	} else {
		a.srv.AddLog("设置已保存", "info")
	}
	// 端口设置已生效；托盘失败只作为警告回传给前端，不回滚偏好。
	return trayErr
}

func shortID(id string) string {
	if len(id) <= 8 {
		return id
	}
	return id[:8] + "..."
}
