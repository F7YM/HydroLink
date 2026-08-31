//go:build windows

package system

import (
	"os/exec"
	"strconv"
	"strings"
)

// batteryInfo queries the battery via PowerShell Win32_Battery.
func batteryInfo() BatteryInfo {
	out, err := exec.Command("powershell", "-NoProfile", "-Command",
		"$b=Get-CimInstance Win32_Battery; if($b){\"$($b.EstimatedChargeRemaining);$($b.BatteryStatus)\"}").Output()
	if err != nil {
		return BatteryInfo{HasBattery: false}
	}
	s := strings.TrimSpace(string(out))
	parts := strings.Split(s, ";")
	if len(parts) < 2 {
		return BatteryInfo{HasBattery: false}
	}
	percent, _ := strconv.Atoi(strings.TrimSpace(parts[0]))
	// BatteryStatus: 2 = AC (charging), 1 = discharging
	status, _ := strconv.Atoi(strings.TrimSpace(parts[1]))
	charging := status == 2
	return BatteryInfo{HasBattery: true, Percent: percent, Charging: charging}
}
