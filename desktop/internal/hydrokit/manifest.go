package hydrokit

import (
	"encoding/json"
	"fmt"
)

// Item is a registered HydroApp or HydroWidget entry.
type Item struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Icon      string `json:"icon,omitempty"`
	RefreshMs int    `json:"refreshMs,omitempty"`
}

// Package identifies the HydroPlugin.
type Package struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Version string `json:"version"`
}

// Manifest describes a HydroPlugin: which apps/widgets it registers and the
// permissions it requests.
type Manifest struct {
	Package     Package  `json:"package"`
	Apps        []Item   `json:"apps,omitempty"`
	Widgets     []Item   `json:"widgets,omitempty"`
	Permissions []string `json:"permissions,omitempty"`
}

// Validate checks required fields and unique item ids per surface.
func (m Manifest) Validate() error {
	if m.Package.ID == "" {
		return fmt.Errorf("hydrokit: manifest missing package.id")
	}
	if len(m.Apps) == 0 && len(m.Widgets) == 0 {
		return fmt.Errorf("hydrokit: manifest registers no apps or widgets")
	}
	if err := uniqueItems("app", m.Apps); err != nil {
		return err
	}
	if err := uniqueItems("widget", m.Widgets); err != nil {
		return err
	}
	return nil
}

func uniqueItems(kind string, items []Item) error {
	seen := map[string]bool{}
	for _, it := range items {
		if it.ID == "" {
			return fmt.Errorf("hydrokit: %s item missing id", kind)
		}
		if seen[it.ID] {
			return fmt.Errorf("hydrokit: duplicate %s id %q", kind, it.ID)
		}
		seen[it.ID] = true
	}
	return nil
}

// HasApp / HasWidget report whether an item id is registered for a surface.
func (m Manifest) HasApp(id string) bool   { return findItem(m.Apps, id) }
func (m Manifest) HasWidget(id string) bool { return findItem(m.Widgets, id) }

func findItem(items []Item, id string) bool {
	for _, it := range items {
		if it.ID == id {
			return true
		}
	}
	return false
}

// ParseManifest decodes and validates a manifest.
func ParseManifest(data []byte) (Manifest, error) {
	var m Manifest
	if err := json.Unmarshal(data, &m); err != nil {
		return m, err
	}
	if err := m.Validate(); err != nil {
		return m, err
	}
	return m, nil
}
