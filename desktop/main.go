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

	"github.com/gen2brain/beeep"
	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/linux"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/gui"
	"hydrolink-desktop/internal/store"
)

//go:embed all:frontend/dist
var assets embed.FS

func init() {
	// beeep shows this as the notification app name (dbus/notify-send -a).
	beeep.AppName = "HydroLink"
}

func main() {
	noGUI := flag.Bool("no-gui", false, "run headless (HTTP server only)")
	port := flag.Int("port", 0, "listen port override")
	flag.Parse()

	st, err := store.New()
	if err != nil {
		log.Fatalf("store: %v", err)
	}
	srv := api.New(st)

	cfg, _ := st.LoadConfig()
	listenPort := *port
	if listenPort == 0 {
		listenPort = cfg.LastPort
	}
	if listenPort == 0 {
		listenPort = 9000
	}
	if *port != 0 && *port != cfg.LastPort {
		cfg.LastPort = listenPort
		_ = st.SaveConfig(cfg)
	}

	addr := fmt.Sprintf("0.0.0.0:%d", listenPort)
	if err := srv.Start(addr); err != nil {
		log.Fatalf("server: %v", err)
	}
	log.Printf("HydroLink desktop server v%s (api_level %d) on %s", api.Version, api.APILevel, addr)
	log.Printf("local IP: %s  |  data dir: %s", api.LocalIP(), st.Dir())

	if *noGUI {
		log.Printf("headless mode — press Ctrl+C to stop")
		select {}
	}

	app := gui.NewApp(srv, st, listenPort)

	err = wails.Run(&options.App{
		Title:     "HydroLink",
		Width:     480,
		Height:    780,
		MinWidth:  400,
		MinHeight: 640,
		AssetServer: &assetserver.Options{
			Assets: assets,
		},
		BackgroundColour: &options.RGBA{R: 18, G: 19, B: 24, A: 1},
		OnStartup:        app.Startup,
		OnShutdown:       app.Shutdown,
		Bind:             []interface{}{app},
		Linux: &linux.Options{
			WindowIsTranslucent: false,
		},
	})
	if err != nil {
		log.Fatalf("wails: %v", err)
	}
}
