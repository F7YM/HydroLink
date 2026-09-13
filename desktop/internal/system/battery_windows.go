//go:build windows

package system

import (
	"os/exec"
	"strconv"
	"strings"
)

// batteryInfo queries the battery via PowerShell Win32_Battery.
func batteryInfo() BatteryInfo {
	cmd := exec.Command("powershell", "-NoProfile", "-Command",
		"$b=Get-CimInstance Win32_Battery; if($b){\"$($b.EstimatedChargeRemaining);$($b.BatteryStatus)\"}")
	// 不隐藏的话每次查电池都会闪一个 PowerShell 黑窗口。
	cmd.SysProcAttr = hideWindowAttr()
	out, err := cmd.Output()
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
