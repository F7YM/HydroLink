//go:build darwin

package system

import (
	"os/exec"
	"strconv"
	"strings"
)

// batteryInfo parses `pmset -g batt` output, e.g.
// "Now drawing from 'Battery Power' -InternalBattery-0 (id=12345678) 87%; discharging;"
func batteryInfo() BatteryInfo {
	out, err := exec.Command("pmset", "-g", "batt").Output()
	if err != nil {
		return BatteryInfo{HasBattery: false}
	}
	line := strings.TrimSpace(string(out))
	idx := strings.Index(line, "%")
	if idx < 0 {
		return BatteryInfo{HasBattery: false}
	}
	// percent is the number before '%'
	start := idx
	for start > 0 && line[start-1] >= '0' && line[start-1] <= '9' {
		start--
	}
	percent, _ := strconv.Atoi(line[start:idx])
	charging := strings.Contains(line, "charging") && !strings.Contains(line, "discharging")
	return BatteryInfo{HasBattery: true, Percent: percent, Charging: charging}
}
