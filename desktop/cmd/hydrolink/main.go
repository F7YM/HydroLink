// HydroLink desktop server — headless entry point (no Wails window).
package main

import (
	"flag"
	"fmt"
	"log"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/app"
)

func main() {
	port := flag.Int("port", 0, "listen port (default: from config, 9000)")
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
	if *port != 0 && *port != cfg.LastPort {
		cfg.LastPort = listenPort
		_ = st.SaveConfig(cfg)
	}

	addr := fmt.Sprintf("0.0.0.0:%d", listenPort)
	log.Printf("HydroLink desktop server v%s (api_level %d)", api.Version, api.APILevel)
	log.Printf("listening on http://%s  (data dir: %s)", addr, st.Dir())
	log.Printf("local IP: %s", api.LocalIP())

	if err := srv.ListenAndServe(addr); err != nil {
		log.Fatalf("server: %v", err)
	}
}
