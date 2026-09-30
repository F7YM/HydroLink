// 一致性测试的 Go 侧工具：把「同一串操作」跑在真 Go store 上，供 Kotlin 侧 diff。
//
// 用法：
//
//	go run ./cmd/parityinit <dir>            # 只初始化目录（用真插件包，与 Kotlin 侧同起点）
//	go run ./cmd/parityinit <dir> --scenario # 再跑一遍与 Kotlin 侧完全相同的操作序列
//
// 场景必须与 desktop-compose 的 StoreParity.scenario() 逐条对应——改了那边就要改这里，
// 否则 diff 出来的差异是「场景不同」而不是「行为不同」。
package main

import (
	"fmt"
	"os"
	"path/filepath"

	"hydrolink-desktop/internal/store"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("用法: parityinit <dir> [--scenario]")
		os.Exit(2)
	}
	dir := os.Args[1]
	st, err := store.NewInDir(dir)
	if err != nil {
		fmt.Println("store:", err)
		os.Exit(1)
	}

	// 初始化：装一个真插件包，让目录结构与真实使用一致（Kotlin 侧会复制这份目录）
	if len(os.Args) < 3 || os.Args[2] != "--scenario" {
		pkg := filepath.Join("examples", "com.demo.weather.hpk")
		if _, err := os.Stat(pkg); err == nil {
			if info, err := st.InstallPlugin(pkg); err != nil {
				fmt.Println("install:", err)
				os.Exit(1)
			} else {
				fmt.Println("已安装示例插件:", info.ID)
			}
		}
		fmt.Println("初始化完成:", dir)
		return
	}

	scenario(st)
	fmt.Println("场景执行完成:", dir)
}

// scenario 与 desktop-compose 的 StoreParity.scenario() 一一对应。
func scenario(st *store.Store) {
	added, err := st.AddPendingPair("dev-a", "手表 A")
	must(err, "addPendingPair dev-a")
	if !added {
		fail("第一次配对请求应当是新的")
	}
	added, err = st.AddPendingPair("dev-a", "手表 A")
	must(err, "addPendingPair dev-a 重复")
	if added {
		fail("同一设备的重复请求不应当重复登记")
	}
	added, err = st.AddPendingPair("dev-b", "手表 B")
	must(err, "addPendingPair dev-b")
	if !added {
		fail("第二个设备应当登记成功")
	}
	must(st.AddRejected("dev-c"), "addRejected dev-c")
	if !st.IsRejected("dev-c") {
		fail("拒绝过的设备应当被记住")
	}
	must(st.RemoveRejected("dev-c"), "removeRejected dev-c")
	if st.IsRejected("dev-c") {
		fail("移除拒绝后不应再被记住")
	}

	must(st.AddTrustedDevice("dev-a", "手表 A"), "addTrustedDevice dev-a")
	must(st.AddTrustedDevice("dev-a", "手表 A"), "addTrustedDevice dev-a 重复")
	must(st.RemovePendingPair("dev-a"), "removePendingPair dev-a")
	if !st.IsTrusted("dev-a") {
		fail("允许后应当已信任")
	}
	pairs, err := st.LoadPendingPairs()
	must(err, "loadPendingPairs")
	for _, p := range pairs {
		if p.DeviceID == "dev-a" {
			fail("允许后待确认列表里不该再有它")
		}
	}

	must(st.AddCommand("重启服务", "systemctl restart hydrolink"), "addCommand 1")
	must(st.AddCommand("锁屏", "loginctl lock-session"), "addCommand 2")
	commands, err := st.LoadCommands()
	must(err, "loadCommands")
	commands[0].Command = "true"
	must(st.SaveCommands(commands), "saveCommands")
	must(st.RemoveCommand(0), "removeCommand 0")
	must(st.RemoveCommand(99), "removeCommand 99 越界")
	after, err := st.LoadCommands()
	must(err, "loadCommands after")
	if len(after) != 1 {
		fail("越界删除不应影响列表")
	}

	cfg, err := st.LoadConfig()
	must(err, "loadConfig")
	cfg.LastPort = 41000
	cfg.AcceptAll = true
	cfg.MarketSource = "https://cdn.jsdelivr.net/gh/F7YM/hydromarket-index@main"
	off := false
	cfg.TrayEnabled = &off
	must(st.SaveConfig(cfg), "saveConfig")
	back, err := st.LoadConfig()
	must(err, "loadConfig back")
	if back.LastPort != 41000 || !back.AcceptAll {
		fail("设置没有正确落盘")
	}
	if back.TrayEnabled == nil || *back.TrayEnabled {
		fail("tray_enabled 显式 false 应当被读回来")
	}
}

func must(err error, what string) {
	if err != nil {
		fmt.Println(what, "->", err)
		os.Exit(1)
	}
}

func fail(msg string) {
	fmt.Println("场景断言失败:", msg)
	os.Exit(1)
}
