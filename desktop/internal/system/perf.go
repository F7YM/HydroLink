package system

import (
	"time"

	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/mem"
)

// Perf holds the performance snapshot returned by /api/performance.
type Perf struct {
	CPU        float64 // percent
	MemPercent float64
	MemUsedMB  uint64
	MemTotalMB uint64
}

// GetPerf samples CPU over 500ms (same semantics as the Android server's
// /proc/stat delta) and reads memory statistics.
func GetPerf() (Perf, error) {
	var p Perf

	// cpu.Percent with a non-zero interval samples over that window and
	// returns the average usage, matching the Android 500ms delta sample.
	if pcts, err := cpu.Percent(500*time.Millisecond, false); err == nil && len(pcts) > 0 {
		p.CPU = pcts[0]
	}

	if vm, err := mem.VirtualMemory(); err == nil {
		p.MemPercent = vm.UsedPercent
		p.MemUsedMB = vm.Used / 1024 / 1024
		p.MemTotalMB = vm.Total / 1024 / 1024
	}

	return p, nil
}
