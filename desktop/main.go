// HydroLink desktop app (Wails): desktop window + HTTP API server.
//
// Build (Linux, webkit2gtk-4.1):
//
//	go build -tags webkit2_41 -o hydrolink .
//
// Run headless (HTTP server only):
//
//	./hydrolink -no-gui
package main

import (
	"embed"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"strings"

	"github.com/gen2brain/beeep"
	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/linux"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/app"
	"hydrolink-desktop/internal/applog"
	"hydrolink-desktop/internal/gui"
	"hydrolink-desktop/internal/system"
	"hydrolink-desktop/internal/tray"
)

//go:embed all:frontend/dist
var assets embed.FS

// Windows 托盘需要 ICO 数据（HICON），Linux 用 PNG。
//
//go:embed icon.ico
var trayICO []byte

func init() {
	// beeep shows this as the notification app name (dbus/notify-send -a).
	beeep.AppName = "HydroLink"
}

// assetAPIProxy forwards page-origin /api/* requests (the plugin worker's
// poll/respond/kit calls and future frontend API calls) to the HydroLink API
// server, keeping the webview same-origin with the API. This avoids worker
// fetch CORS/mixed-content failures entirely — the worker uses relative URLs.
func assetAPIProxy(srv *api.Server) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			srv.Handler().ServeHTTP(w, r)
			return
		}
		http.NotFound(w, r)
	})
}

func main() {
	noGUI := flag.Bool("no-gui", false, "run headless (HTTP server only)")
	port := flag.Int("port", 0, "listen port override")
	musicProbe := flag.Bool("music-probe", false, "打印当前媒体信息（诊断用）后退出")
	trayCheck := flag.Bool("tray-check", false, "诊断托盘图标数据与注册（起一次托盘后退出）")
	flag.Parse()

	// Window / taskbar icon (GTK on Linux; Windows uses the embedded .syso) —
	// the same PNG feeds the system tray pixmap.
	windowIconBytes, _ := assets.ReadFile("frontend/dist/icon.png")

	if *trayCheck {
		// 诊断"Windows 托盘不出来"：打印真实嵌入的图标数据量，并分别用
		// ICO 与 PNG 两条路径各起一次托盘（wine 下也能跑，能验证 Win32 调用）。
		fmt.Printf("icon.png=%d bytes, icon.ico=%d bytes\n", len(windowIconBytes), len(trayICO))
		log.Printf("tray-check: png=%d ico=%d", len(windowIconBytes), len(trayICO))
		try := func(name string, ico, png []byte) {
			tr, err := tray.Start(tray.Options{
				IconPNG: png, IconICO: ico, Title: "HydroLink",
				Logf: func(f string, a ...any) { fmt.Printf("[%s] "+f+"\n", append([]any{name}, a...)...) },
			})
			if err != nil {
				fmt.Printf("[%s] 失败: %v\n", name, err)
				log.Printf("tray-check %s 失败: %v", name, err)
				return
			}
			fmt.Printf("[%s] 注册成功\n", name)
			tr.Stop()
		}
		try("ICO", trayICO, windowIconBytes)
		try("PNG", nil, windowIconBytes)
		return
	}

	if *musicProbe {
		// 一条命令诊断"手表看不到音乐"：Windows 用 GUI 子系统构建时没有控制台，
		// 所以同时写日志文件；控制台版可直接看输出。
		out, _ := json.Marshal(map[string]any{
			"music": system.GetMusic(),
			"error": system.MusicDiagnostics(),
		})
		fmt.Println(string(out))
		log.Printf("music-probe: %s", out)
		return
	}

	srv, st, err := app.NewServer()
	if err != nil {
		log.Fatalf("init: %v", err)
	}
	// 文件日志（Windows GUI 子系统没有控制台，日志必须落盘）
	if err := applog.Init(st.Dir()); err != nil {
		log.Printf("warning: 日志文件不可用，仅输出到控制台: %v", err)
	} else {
		defer applog.Close()
		srv.SetLogWriter(applog.Stdout())
	}

	cfg, _ := st.LoadConfig()
	listenPort := *port
	if listenPort == 0 {
		listenPort = cfg.LastPort
	}
	if listenPort == 0 {
		listenPort = 9000
	}
	// NOTE: a -port flag only overrides THIS run. It is deliberately NOT
	// persisted to cfg.LastPort — only a manual GUI port change (SaveSettings)
	// updates the remembered port, so ad-hoc runs never change the default.

	log.Printf("HydroLink desktop server v%s (api_level %d)", api.Version, api.APILevel)
	ipInfo := api.LocalIPInfo()
	log.Printf("local IP: %s (iface %s, %s)  |  data dir: %s", ipInfo.IP, ipInfo.Iface, ipInfo.Source, st.Dir())

	if *noGUI {
		addr := fmt.Sprintf("0.0.0.0:%d", listenPort)
		if err := srv.Start(addr); err != nil {
			if api.AnotherInstanceRunning(listenPort) {
				log.Fatalf("已有 HydroLink 实例在运行（端口 %d），本次启动退出", listenPort)
			}
			log.Fatalf("%v", api.BindError(listenPort, err))
		}
		log.Printf("listening on %s", addr)
		log.Printf("headless mode — press Ctrl+C to stop")
		select {}
	}

	desktopApp := gui.NewApp(srv, st, listenPort, windowIconBytes, trayICO)

	// Start the HTTP server before the window so the watch can already connect
	// while the UI loads. A bind failure is NOT fatal here: the usual cause is
	// a second launch, and Wails' single-instance lock exits that one (the
	// first instance gets OnSecondInstanceLaunch). If the port is genuinely
	// taken by another program, Startup retries and shows a dialog.
	if err := desktopApp.StartServer(); err != nil {
		log.Printf("warning: %v", err)
	} else {
		log.Printf("listening on 0.0.0.0:%d", listenPort)
	}

	err = wails.Run(&options.App{
		Title:     "HydroLink",
		Width:     960,
		Height:    640,
		MinWidth:  820,
		MinHeight: 560,
		AssetServer: &assetserver.Options{
			Assets:  assets,
			Handler: assetAPIProxy(srv),
		},
		BackgroundColour: &options.RGBA{R: 18, G: 19, B: 24, A: 1},
		OnStartup:        desktopApp.Startup,
		OnShutdown:       desktopApp.Shutdown,
		OnBeforeClose:    desktopApp.OnBeforeClose,
		// Second launch focuses the existing instance instead of dying on a
		// port conflict (Windows users double-click the exe twice all the time).
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId:               "com.hydrolink.desktop",
			OnSecondInstanceLaunch: desktopApp.OnSecondInstance,
		},
		Bind: []interface{}{desktopApp},
		Linux: &linux.Options{
			WindowIsTranslucent: false,
			Icon:                windowIconBytes,
			ProgramName:         "hydrolink",
		},
	})
	if err != nil {
		log.Fatalf("wails: %v", err)
	}
}
