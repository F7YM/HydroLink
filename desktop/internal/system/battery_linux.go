//go:build linux

package system

import (
	"os"
	"strconv"
	"strings"
)

// batteryInfo reads the first power supply battery from /sys/class/power_supply.
func batteryInfo() BatteryInfo {
	dirs, err := os.ReadDir("/sys/class/power_supply")
	if err != nil {
		return BatteryInfo{HasBattery: false}
	}
	for _, d := range dirs {
		name := d.Name()
		if !strings.HasPrefix(name, "BAT") {
			continue
		}
		base := "/sys/class/power_supply/" + name
		capData, err := os.ReadFile(base + "/capacity")
		if err != nil {
			continue
		}
		percent, _ := strconv.Atoi(strings.TrimSpace(string(capData)))
		status := ""
		if s, err := os.ReadFile(base + "/status"); err == nil {
			status = strings.TrimSpace(string(s))
		}
		charging := status == "Charging" || status == "Full"
		return BatteryInfo{HasBattery: true, Percent: percent, Charging: charging}
	}
	return BatteryInfo{HasBattery: false}
}
