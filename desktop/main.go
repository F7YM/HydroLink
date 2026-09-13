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
	"strings"

	"github.com/gen2brain/beeep"
	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/linux"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/app"
	"hydrolink-desktop/internal/gui"
)

//go:embed all:frontend/dist
var assets embed.FS

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
	flag.Parse()

	srv, st, err := app.NewServer()
	if err != nil {
		log.Fatalf("init: %v", err)
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

	// Window / taskbar icon (GTK on Linux; Windows uses the embedded .syso) —
	// the same PNG feeds the system tray pixmap.
	windowIcon, _ := assets.ReadFile("frontend/dist/icon.png")

	desktopApp := gui.NewApp(srv, st, listenPort, windowIcon)

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
			Icon:                windowIcon,
			ProgramName:         "hydrolink",
		},
	})
	if err != nil {
		log.Fatalf("wails: %v", err)
	}
}
