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
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/linux"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/app"
	"hydrolink-desktop/internal/applog"
	"hydrolink-desktop/internal/gui"
)

// showWindowOnStartup 恒为 true：窗口由 Wails 在 DOM 就绪后显示（见下方
// StartHidden 处的说明）。保留成常量是为了让 main_test.go 能钉住它。
const showWindowOnStartup = true

//go:embed all:frontend/dist
var assets embed.FS

// fatalExit 报告致命错误并退出：写日志 + 尽力弹原生错误框。
//
// 之前直接 log.Fatalf，而 GUI 子系统（Windows）和 .desktop 启动（Linux）都
// 看不到 stdout —— 失败表现为"闪一下就退出"，无法排查。
func fatalExit(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	log.Printf("fatal: %s", msg)
	fatalDialog("HydroLink 无法启动", msg)
	os.Exit(1)
}

// Windows 托盘需要 ICO 数据（HICON），Linux 用 PNG。
//
//go:embed icon.ico
var trayICO []byte

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
	showVersion := flag.Bool("version", false, "print version and exit")
	port := flag.Int("port", 0, "listen port override")
	flag.Parse()

	if *showVersion {
		fmt.Printf("HydroLink %s (api_level %d)\n", api.Version, api.APILevel)
		return
	}

	// Window / taskbar icon (GTK on Linux; Windows uses the embedded .syso) —
	// the same PNG feeds the system tray pixmap.
	windowIconBytes, _ := assets.ReadFile("frontend/dist/icon.png")

	srv, st, err := app.NewServer()
	if err != nil {
		fatalExit("初始化失败: %v", err)
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
				fatalExit("已有 HydroLink 实例在运行（端口 %d），本次启动退出", listenPort)
			}
			fatalExit("%v", api.BindError(listenPort, err))
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
		Title: "HydroLink",
		// 窗口必须交给 Wails 自己显示（onDomReady 时 ShowWindow）。
		//
		// 千万别开 StartHidden：Wails 的 Windows 实现在 onDomReady 里
		// `if StartHidden { return }` —— 直接不显示窗口；而 OnStartup 是并发的
		// 协程、跑在窗口就绪之前，从那里调 WindowShow 是无效的。结果是进程活着
		// 但窗口永远不出现（托盘关掉时毫无入口，第二次启动才会被单实例通知捞
		// 出来）。Linux 实现因为 Hide() 之后又 gtk_widget_show_all() 而看不出问题。
		StartHidden: !showWindowOnStartup,
		Width:       960,
		Height:      640,
		MinWidth:    820,
		MinHeight:   560,
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
		fatalExit("窗口创建失败: %v", err)
	}
}
