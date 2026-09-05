// Package gui provides the Wails-bound desktop application: status, pairing,
// commands, settings and log views over the shared API server.
package gui

import (
	"context"
	"fmt"

	"github.com/gen2brain/beeep"
	"github.com/wailsapp/wails/v2/pkg/runtime"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/store"
)

// App is bound to the Wails frontend (window.go.gui.App.*).
type App struct {
	ctx  context.Context
	srv  *api.Server
	st   *store.Store
	port int
}

// NewApp creates the desktop app bindings.
func NewApp(srv *api.Server, st *store.Store, port int) *App {
	return &App{srv: srv, st: st, port: port}
}

// Startup is called by Wails when the window is ready.
func (a *App) Startup(ctx context.Context) {
	a.ctx = ctx
	a.srv.AddLog("桌面端已启动", "success")
	// Popup + system notification on every new pairing request.
	a.srv.SetPairCallback(a.onPairRequest)
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

// Shutdown is called by Wails on exit; stops the HTTP server.
func (a *App) Shutdown(ctx context.Context) {
	a.srv.Stop()
}

// Status is the snapshot shown on the status tab.
type Status struct {
	Version        string              `json:"version"`
	APILevel       int                 `json:"apiLevel"`
	Port           int                 `json:"port"`
	IP             string              `json:"ip"`
	DataDir        string              `json:"dataDir"`
	AcceptAll      bool                `json:"acceptAll"`
	EncryptionKey  string              `json:"encryptionKey"`
	TrustedCount   int                 `json:"trustedCount"`
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

// Settings holds the editable server settings.
type Settings struct {
	Port      int  `json:"port"`
	AcceptAll bool `json:"acceptAll"`
}

// GetSettings returns the current settings.
func (a *App) GetSettings() Settings {
	cfg, _ := a.st.LoadConfig()
	return Settings{Port: a.port, AcceptAll: cfg.AcceptAll}
}

// SaveSettings persists settings; changing the port restarts the HTTP server.
func (a *App) SaveSettings(port int, acceptAll bool) error {
	if port < 1 || port > 65535 {
		return fmt.Errorf("端口无效")
	}
	cfg, _ := a.st.LoadConfig()
	cfg.AcceptAll = acceptAll
	cfg.LastPort = port
	if err := a.st.SaveConfig(cfg); err != nil {
		return err
	}
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
	return nil
}

func shortID(id string) string {
	if len(id) <= 8 {
		return id
	}
	return id[:8] + "..."
}
