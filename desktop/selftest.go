package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"runtime"
	"strings"
	"time"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/system"
	"hydrolink-desktop/internal/tray"
)

// -selftest 的实现：把"可疑的运行时动作"逐项单独跑一遍并打印结果。
//
// 用途：Windows 上出现"手表一连接、主窗口和托盘一起消失"这类**硬崩**（访问违例
// 会直接干掉进程，Go 的 recover 拦不住，日志也来不及写）。在真机上逐项跑就能
// 定位到是哪一项：崩溃时最后打印出来的是"开始 xxx"，那一项就是元凶。
//
// 用法：
//
//	HydroLink-amd64-console.exe -selftest=all
//	HydroLink-amd64-console.exe -selftest=music,cover
//	HydroLink-amd64-console.exe -selftest=notify
//	HydroLink-amd64-console.exe -selftest=tray
//
// GUI 子系统（无控制台）的构建同样可用，输出会写进 <数据目录>/hydrolink.log。
// selfTestTimes 是 -selftest=loop 的轮数（-selftest-times 覆盖）。
var selfTestTimes = 30

func runSelfTest(items string) {
	icons := loadedIcons()

	fmt.Printf("=== HydroLink 自检 ===\n")
	fmt.Printf("os=%s arch=%s data=%s png=%dB ico=%dB\n",
		runtime.GOOS, runtime.GOARCH, dataDirHint(), len(icons.png), len(icons.ico))
	log.Printf("selftest: os=%s arch=%s items=%s", runtime.GOOS, runtime.GOARCH, items)

	for _, raw := range strings.Split(items, ",") {
		name := strings.TrimSpace(raw)
		if name == "" {
			continue
		}
		if name == "all" {
			for _, n := range []string{"music", "cover", "notify", "tray", "loop"} {
				step(n)
			}
			continue
		}
		step(name)
	}
	fmt.Printf("=== 自检结束（说明每一项都活着）===\n")
	log.Printf("selftest: 全部完成")
}

// step 执行单项自检。刻意分两行打印（开始/结果）：硬崩时"开始"就是最后的线索。
func step(name string) {
	fmt.Printf("[%s] 开始…\n", name)
	log.Printf("selftest: [%s] 开始", name)

	switch name {
	case "music":
		start := time.Now()
		info, err := system.MusicSelfTest()
		b, _ := json.Marshal(info)
		if err != nil {
			fmt.Printf("[music] 失败(%s): %v\n", time.Since(start).Round(time.Millisecond), err)
			return
		}
		fmt.Printf("[music] ok(%s): %s\n", time.Since(start).Round(time.Millisecond), b)

	case "cover":
		start := time.Now()
		url, err := system.CoverArtSelfTest()
		if err != nil {
			fmt.Printf("[cover] 失败(%s): %v\n", time.Since(start).Round(time.Millisecond), err)
			return
		}
		fmt.Printf("[cover] ok(%s): %s\n", time.Since(start).Round(time.Millisecond), url)

	case "notify":
		// beeep → Windows 走 go-toast 的 WinRT COM（手搓 vtable + 写注册表），
		// 是另一个"可能整进程崩"的点。
		if err := system.Notify("HydroLink 自检", "如果你看到这条通知，通知链路正常"); err != nil {
			fmt.Printf("[notify] 失败: %v\n", err)
			return
		}
		fmt.Printf("[notify] ok\n")

	case "tray":
		icons := loadedIcons()
		tr, err := tray.Start(tray.Options{
			IconPNG: icons.png, IconICO: icons.ico, Title: "HydroLink 自检",
			Logf: func(f string, a ...any) { fmt.Printf("[tray] "+f+"\n", a...) },
		})
		if err != nil {
			fmt.Printf("[tray] 失败: %v\n", err)
			return
		}
		tr.Stop()
		fmt.Printf("[tray] ok\n")

	case "loop":
		// 模拟手表在设备卡片页的轮询（music + 封面每 3 秒一次），
		// 崩溃时"第 N 次 / music|cover"就是最后线索。
		times := selfTestTimes
		for i := 1; i <= times; i++ {
			fmt.Printf("[loop] 第 %d/%d 次: music…\n", i, times)
			log.Printf("selftest: [loop] 第 %d/%d 次 music", i, times)
			if info, err := system.MusicSelfTest(); err != nil {
				fmt.Printf("[loop] 第 %d 次 music 失败: %v\n", i, err)
			} else {
				b, _ := json.Marshal(info)
				fmt.Printf("[loop] 第 %d 次 music ok: %s\n", i, b)
			}
			fmt.Printf("[loop] 第 %d/%d 次: cover…\n", i, times)
			log.Printf("selftest: [loop] 第 %d/%d 次 cover", i, times)
			if url, err := system.CoverArtSelfTest(); err != nil {
				fmt.Printf("[loop] 第 %d 次 cover 失败: %v\n", i, err)
			} else {
				fmt.Printf("[loop] 第 %d 次 cover ok: %s\n", i, url)
			}
			time.Sleep(200 * time.Millisecond)
		}

	case "ip":
		info := api.LocalIPInfo()
		fmt.Printf("[ip] %s (iface %s, %s)\n", info.IP, info.Iface, info.Source)

	default:
		fmt.Printf("[%s] 未知自检项（可用: music,cover,notify,tray,ip,loop,all）\n", name)
	}
}

func dataDirHint() string {
	cfg, err := os.UserConfigDir()
	if err != nil {
		return "?"
	}
	return cfg + string(os.PathSeparator) + "hydrolink"
}
