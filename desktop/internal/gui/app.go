// Package gui provides the Wails-bound desktop application: status, pairing,
// commands, settings and log views over the shared API server.
package gui

import (
	"context"
	"fmt"
	"os"

	"github.com/gen2brain/beeep"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/runtime"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/applog"
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
	trayICO     []byte
	tray        *tray.Tray
	trayErr     string

	// quitting 由托盘"退出"置位。Wails 的 Quit() 会先问 OnBeforeClose
	// （用于"关闭=最小化到托盘"），若那里仍返回 true，退出就被自己拦掉了。
	quitting bool
}

// NewApp creates the desktop app bindings. trayIcon is the PNG used for the
// system tray pixmap (usually frontend/dist/icon.png).
func NewApp(srv *api.Server, st *store.Store, port int, trayIcon, trayICO []byte) *App {
	a := &App{srv: srv, st: st, port: port, trayIcon: trayIcon, trayICO: trayICO}
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
	// 记下实际拿到的图标数据量：托盘起不来时这是第一个要看的地方
	// （Windows 上曾出现"缺少 ICO 图标数据"就是因为构建/参数没接上）。
	a.srv.AddLog(fmt.Sprintf("托盘图标数据: PNG %d 字节 / ICO %d 字节", len(a.trayIcon), len(a.trayICO)), "info")
	t, err := tray.Start(tray.Options{
		IconPNG:  a.trayIcon,
		Title:    "HydroLink",
		Subtitle: "桌面服务端",
		Logf: func(format string, args ...any) {
			a.srv.AddLog(fmt.Sprintf(format, args...), "info")
		},
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
	a.srv.AddLog("窗口已显示", "info")
}

// Quit exits the app (tray menu 退出).
func (a *App) Quit() {
	a.srv.AddLog("收到退出请求，正在退出", "info")
	// 必须先置位：Wails Linux/Windows 的 Quit() 会回调 OnBeforeClose，
	// 否则"关闭=最小化到托盘"会把退出请求当成关窗而拦下。
	a.quitting = true
	if a.ctx != nil {
		runtime.Quit(a.ctx)
	}
}

// closeDecision reports whether a window-close request should only hide the
// window (tray icon active) instead of quitting.
//
// 这条规则曾经把托盘"退出"一起吃掉：Wails 的 Quit() 也会走 OnBeforeClose，
// 只要它还返回 true，mainWindow.Quit() 就永远不执行。
func closeDecision(quitting, trayRunning bool) bool {
	return !quitting && trayRunning
}

// OnBeforeClose hides the window instead of quitting while the tray icon is
// active, so the HTTP server keeps serving the watch.
func (a *App) OnBeforeClose(ctx context.Context) bool {
	if !closeDecision(a.quitting, a.tray.Running()) {
		return false
	}
	runtime.WindowHide(ctx)
	a.srv.AddLog("窗口已最小化到托盘（服务继续运行）", "info")
	return true
}

// StartServer binds the HTTP listener. main calls it before wails.Run so the
// server is up before the window loads; a failure is not fatal there because
// the most common cause is a second launch, which the single-instance lock
// handles (and Startup retries the bind).
func (a *App) StartServer() error {
	if a.srv.Started() {
		return nil
	}
	if err := a.srv.Start(fmt.Sprintf("0.0.0.0:%d", a.port)); err != nil {
		return api.BindError(a.port, err)
	}
	a.srv.AddLog(fmt.Sprintf("服务已启动，监听端口 %d", a.port), "success")
	return nil
}

// OnSecondInstance is called in the ALREADY RUNNING instance when the user
// launches HydroLink again: bring the window back instead of erroring.
func (a *App) OnSecondInstance(data options.SecondInstanceData) {
	a.srv.AddLog("检测到第二次启动，已唤起现有窗口", "info")
	a.ShowWindow()
}

// Startup is called by Wails when the window is ready.
//
// 窗口用 StartHidden 创建（见 main.go）：Wails 在 Startup 之前就把窗口建出来了，
// 若启动阶段就要退出（第二次启动、端口被占后放弃），用户会看到窗口"闪一下就没了"。
// 这里在确认要继续运行时才显式显示窗口。
func (a *App) Startup(ctx context.Context) {
	a.ctx = ctx
	showOnSuccess := true
	defer func() {
		if showOnSuccess {
			a.ShowWindow()
		}
	}()
	// The listener may still be missing if main could not bind it (port taken
	// by a foreign program). Retry once and surface a real dialog if it fails.
	if !a.srv.Started() {
		if err := a.StartServer(); err != nil {
			// 端口被占先分清"自己人"还是"别人"：
			if api.AnotherInstanceRunning(a.port) {
				// 第二次启动（通常双击了两次）。单实例锁会把请求转给在跑的
				// 实例，这里安静退出即可。注意 runtime.Quit 在 GTK 主循环起来
				// 之前是空操作（只会打 gtk_main_quit 断言），所以直接退出进程。
				// 窗口始终不显示 → 用户不会再看到"闪一下"。
				showOnSuccess = false
				a.srv.AddLog("已有实例在运行，本次启动退出", "info")
				os.Exit(0)
			}
			// 被别的程序占用：不要退出，也不要让窗口显示成一个"看似正常但
			// 服务没起来"的状态——记日志 + 尽力弹窗，窗口留着，用户可以在
			// 设置里改端口（SaveSettings 会重新绑定）。
			a.srv.AddLog("启动失败: "+err.Error()+"（可在设置里更换端口）", "warning")
			go func() {
				_, _ = runtime.MessageDialog(ctx, runtime.MessageDialogOptions{
					Type:    runtime.ErrorDialog,
					Title:   "HydroLink 服务未能启动",
					Message: err.Error() + "\n\n可在「设置」里换一个端口后保存。",
				})
			}()
		}
	}
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
	LogFile        string                `json:"logFile"`
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
		LogFile:        applog.Path(),
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
