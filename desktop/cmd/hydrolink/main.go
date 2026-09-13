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
	// NOTE: a -port flag only overrides THIS run. It is deliberately NOT
	// persisted to cfg.LastPort — only a manual GUI port change updates the
	// remembered port, so ad-hoc runs never change the default.

	addr := fmt.Sprintf("0.0.0.0:%d", listenPort)
	log.Printf("HydroLink desktop server v%s (api_level %d)", api.Version, api.APILevel)
	log.Printf("listening on http://%s  (data dir: %s)", addr, st.Dir())
	ipInfo := api.LocalIPInfo()
	log.Printf("local IP: %s (iface %s, %s)", ipInfo.IP, ipInfo.Iface, ipInfo.Source)

	if err := srv.ListenAndServe(addr); err != nil {
		log.Fatalf("server: %v", err)
	}
}
