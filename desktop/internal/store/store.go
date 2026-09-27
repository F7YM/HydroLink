// Package store implements JSON persistence for the HydroLink desktop
// server. File names and JSON field names match the Android version so the
// protocol stays byte-compatible.
package store

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// TrustedDevice mirrors Android's model.TrustedDevice.
type TrustedDevice struct {
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
	TrustedAt  string `json:"trusted_at"`
}

// PairRequest mirrors Android's model.PairRequest.
type PairRequest struct {
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
}

// Command mirrors Android's model.Command.
type Command struct {
	Name    string `json:"name"`
	Command string `json:"command"`
}

// Config mirrors Android's model.AppConfig.
type Config struct {
	EncryptionKey string `json:"encryption_key"`
	LastPort      int    `json:"last_port"`
	AcceptAll     bool   `json:"accept_all"`
	UseShizuku    bool   `json:"use_shizuku"` // kept for compatibility; unused on desktop
	// MarketSource 插件市场来源：空或 "auto" 表示按候选表依次尝试
	// （jsDelivr → GitHub Raw → Gh-Proxy），也可以是用户自填的基址。
	MarketSource string `json:"market_source,omitempty"`
	// TrayEnabled 控制是否在系统托盘区显示图标（关闭窗口时最小化到托盘、服务继续跑）。
	// 指针语义：**默认开启**——老配置文件里没有这个键（nil）也算开，只有显式写 false
	// 才是用户关掉了托盘。
	TrayEnabled *bool `json:"tray_enabled,omitempty"`
}

// Store owns the data directory and all persisted state.
type Store struct {
	mu sync.Mutex

	dir string

	trustedFile  string
	commandsFile string
	configFile   string
	rejectedFile string
	pendingFile  string
}

// New returns a Store rooted at the platform-appropriate data directory
// (os.UserConfigDir()/hydrolink, falling back to ~/.hydrolink).
func New() (*Store, error) {
	dir, err := dataDir()
	if err != nil {
		return nil, err
	}
	return NewInDir(dir)
}

// NewInDir 是在指定目录开一个 Store（测试与多实例用；生产走 New）。
func NewInDir(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("create data dir: %w", err)
	}
	s := &Store{
		dir:          dir,
		trustedFile:  filepath.Join(dir, "trusted_devices.json"),
		commandsFile: filepath.Join(dir, "commands.json"),
		configFile:   filepath.Join(dir, "config.json"),
		rejectedFile: filepath.Join(dir, "rejected_devices.json"),
		pendingFile:  filepath.Join(dir, "pending_pair.json"),
	}
	// Ensure config exists with a generated encryption key (like Android).
	cfg, err := s.LoadConfig()
	if err != nil {
		return nil, err
	}
	if cfg.EncryptionKey == "" {
		cfg.EncryptionKey = newEncryptionKey()
		if err := s.SaveConfig(cfg); err != nil {
			return nil, err
		}
	}
	return s, nil
}

// Dir returns the resolved data directory.
func (s *Store) Dir() string { return s.dir }

// ---- Trusted devices ----

func (s *Store) LoadTrustedDevices() ([]TrustedDevice, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return loadJSON(s.trustedFile, []TrustedDevice{})
}

func (s *Store) SaveTrustedDevices(devices []TrustedDevice) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return saveJSON(s.trustedFile, devices)
}

func (s *Store) IsTrusted(deviceID string) bool {
	devices, _ := s.LoadTrustedDevices()
	for _, d := range devices {
		if d.DeviceID == deviceID {
			return true
		}
	}
	return false
}

func (s *Store) AddTrustedDevice(deviceID, deviceName string) error {
	devices, err := s.LoadTrustedDevices()
	if err != nil {
		return err
	}
	for _, d := range devices {
		if d.DeviceID == deviceID {
			return nil // already trusted
		}
	}
	devices = append(devices, TrustedDevice{
		DeviceID:   deviceID,
		DeviceName: deviceName,
		TrustedAt:  time.Now().Format("2006-01-02 15:04:05"),
	})
	return s.SaveTrustedDevices(devices)
}

func (s *Store) RemoveTrustedDevice(deviceID string) error {
	devices, err := s.LoadTrustedDevices()
	if err != nil {
		return err
	}
	out := devices[:0]
	for _, d := range devices {
		if d.DeviceID != deviceID {
			out = append(out, d)
		}
	}
	return s.SaveTrustedDevices(out)
}

// ---- Rejected devices ----

func (s *Store) IsRejected(deviceID string) bool {
	list, _ := s.loadRejected()
	for _, id := range list {
		if id == deviceID {
			return true
		}
	}
	return false
}

func (s *Store) AddRejected(deviceID string) error {
	list, err := s.loadRejected()
	if err != nil {
		return err
	}
	for _, id := range list {
		if id == deviceID {
			return nil
		}
	}
	list = append(list, deviceID)
	s.mu.Lock()
	defer s.mu.Unlock()
	return saveJSON(s.rejectedFile, list)
}

// RemoveRejected forgets a prior rejection so a new pairing attempt is
// prompted again instead of being auto-rejected forever.
func (s *Store) RemoveRejected(deviceID string) error {
	list, err := s.loadRejected()
	if err != nil {
		return err
	}
	out := list[:0]
	for _, id := range list {
		if id != deviceID {
			out = append(out, id)
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return saveJSON(s.rejectedFile, out)
}

func (s *Store) loadRejected() ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return loadJSON(s.rejectedFile, []string{})
}

// ---- Pending pairs ----

func (s *Store) LoadPendingPairs() ([]PairRequest, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return loadJSON(s.pendingFile, []PairRequest{})
}

// AddPendingPair records a pairing request; returns true when the request is
// new (false if the same device is already pending).
func (s *Store) AddPendingPair(deviceID, deviceName string) (bool, error) {
	pairs, err := s.LoadPendingPairs()
	if err != nil {
		return false, err
	}
	for _, p := range pairs {
		if p.DeviceID == deviceID {
			return false, nil
		}
	}
	pairs = append(pairs, PairRequest{DeviceID: deviceID, DeviceName: deviceName})
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := saveJSON(s.pendingFile, pairs); err != nil {
		return false, err
	}
	return true, nil
}

func (s *Store) RemovePendingPair(deviceID string) error {
	pairs, err := s.LoadPendingPairs()
	if err != nil {
		return err
	}
	out := pairs[:0]
	for _, p := range pairs {
		if p.DeviceID != deviceID {
			out = append(out, p)
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return saveJSON(s.pendingFile, out)
}

// ---- Commands ----

func (s *Store) LoadCommands() ([]Command, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return loadJSON(s.commandsFile, []Command{})
}

func (s *Store) SaveCommands(commands []Command) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return saveJSON(s.commandsFile, commands)
}

func (s *Store) AddCommand(name, command string) error {
	commands, err := s.LoadCommands()
	if err != nil {
		return err
	}
	commands = append(commands, Command{Name: name, Command: command})
	return s.SaveCommands(commands)
}

func (s *Store) RemoveCommand(index int) error {
	commands, err := s.LoadCommands()
	if err != nil {
		return err
	}
	if index < 0 || index >= len(commands) {
		return nil
	}
	commands = append(commands[:index], commands[index+1:]...)
	return s.SaveCommands(commands)
}

// ---- Config ----

func (s *Store) LoadConfig() (Config, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cfg, err := loadJSON(s.configFile, Config{})
	if err != nil {
		return Config{}, err
	}
	if cfg.LastPort == 0 {
		cfg.LastPort = 9000
	}
	return cfg, nil
}

func (s *Store) SaveConfig(cfg Config) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return saveJSON(s.configFile, cfg)
}

// ---- JSON helpers ----

func loadJSON[T any](path string, def T) (T, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return def, nil
		}
		return def, err
	}
	if len(data) == 0 {
		return def, nil
	}
	var out T
	if err := json.Unmarshal(data, &out); err != nil {
		return def, err
	}
	return out, nil
}

func saveJSON(path string, v any) error {
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func dataDir() (string, error) {
	if cfg, err := os.UserConfigDir(); err == nil && cfg != "" {
		dir := filepath.Join(cfg, "hydrolink")
		// macOS: os.UserConfigDir returns ~/Library/Application Support,
		// which is the conventional app-data location there.
		return dir, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".hydrolink"), nil
}

func newEncryptionKey() string {
	// Same scheme as Android: two UUIDs concatenated without dashes.
	u := func() string {
		b := make([]byte, 16)
		if _, err := rand.Read(b); err != nil {
			return fmt.Sprintf("%x", time.Now().UnixNano())
		}
		b[6] = (b[6] & 0x0f) | 0x40
		b[8] = (b[8] & 0x3f) | 0x80
		return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
	}
	return stripDashes(u()) + stripDashes(u())
}

func stripDashes(s string) string {
	out := make([]byte, 0, len(s))
	for _, c := range s {
		if c != '-' {
			out = append(out, byte(c))
		}
	}
	return string(out)
}

// Sort helpers used by tests/UI.
func SortTrusted(devices []TrustedDevice) {
	sort.Slice(devices, func(i, j int) bool { return devices[i].TrustedAt < devices[j].TrustedAt })
}
