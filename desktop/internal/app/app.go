// Package app wires the HydroLink API server together with the HydroKit
// plugin platform, shared by the GUI and headless entry points.
package app

import (
	"fmt"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/hydrokit"
	"hydrolink-desktop/internal/hydrokit/demo"
	"hydrolink-desktop/internal/store"
)

// NewServer builds a store + API server with the HydroKit dispatcher mounted
// and the bundled plugins registered.
func NewServer() (*api.Server, *store.Store, error) {
	st, err := store.New()
	if err != nil {
		return nil, nil, fmt.Errorf("store: %w", err)
	}
	srv := api.New(st)

	// HydroKit: register bundled plugins, mount the /api/hydro dispatcher.
	rt := hydrokit.NewRuntime(nil)
	for _, p := range []hydrokit.Plugin{demo.NewWeather()} {
		if err := rt.Register(p); err != nil {
			return nil, nil, fmt.Errorf("hydrokit: %w", err)
		}
	}
	srv.SetHydroHandler(hydrokit.NewHandler(rt))

	return srv, st, nil
}
