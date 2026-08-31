// HydroStation desktop server — entry point.
//
// Starts the HTTP API server (byte-compatible with the Android version) and,
// once the Wails GUI layer is wired in, opens the desktop window. Until then
// the server runs headless: run `hydrostation` to serve on port 9000.
package main

import (
	"flag"
	"fmt"
	"log"

	"hydrostation-desktop/internal/api"
	"hydrostation-desktop/internal/store"
)

func main() {
	port := flag.Int("port", 0, "listen port (default: from config, 9000)")
	noLog := flag.Bool("quiet", false, "suppress request logging")
	_ = noLog
	flag.Parse()

	st, err := store.New()
	if err != nil {
		log.Fatalf("store: %v", err)
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

	srv := api.New(st)
	addr := fmt.Sprintf("0.0.0.0:%d", listenPort)
	log.Printf("HydroStation desktop server v%s (api_level %d)", api.Version, api.APILevel)
	log.Printf("listening on http://%s  (data dir: %s)", addr, st.Dir())
	log.Printf("local IP: %s", api.LocalIP())

	if err := srv.ListenAndServe(addr); err != nil {
		log.Fatalf("server: %v", err)
	}
}
