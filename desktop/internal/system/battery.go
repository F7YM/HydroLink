package system

// BatteryInfo mirrors Android's BatteryInfo.
type BatteryInfo struct {
	HasBattery bool
	Percent    int
	Charging   bool
}

// GetBattery returns battery presence/percent/charging. Desktops without a
// battery report HasBattery=false (matching Android's BatteryInfo(false,0,false)).
// Implemented per platform.
func GetBattery() BatteryInfo {
	return batteryInfo()
}
