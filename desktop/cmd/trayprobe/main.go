//go:build windows

// trayprobe 是 Windows 托盘的诊断探针：在真实 Windows（或 wine）里直接调用
// tray.Start/Stop，打印每一步结果。用来验证无法在 Linux 上测到的部分——
// ICO 解析、HICON 创建、NOTIFYICONDATAW 结构体布局与 Shell_NotifyIcon 调用。
//
// 用法（在 Windows 上）：
//
//	go run ./cmd/trayprobe            # 显示 5 秒后自动移除
//	go run ./cmd/trayprobe -secs 30
package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"hydrolink-desktop/internal/tray"
)

func main() {
	secs := flag.Int("secs", 5, "保持图标显示的秒数")
	icoPath := flag.String("ico", "icon.ico", "ICO 图标文件路径")
	menuOnly := flag.Bool("menu", false, "只检查菜单构建（不显示图标）")
	flag.Parse()

	if *menuOnly {
		if err := tray.CheckMenu(); err != nil {
			fmt.Println("菜单构建失败:", err)
			os.Exit(3)
		}
		fmt.Println("菜单构建成功（显示主窗口 / ─── / 退出）")
		return
	}

	ico, err := os.ReadFile(*icoPath)
	if err != nil {
		fmt.Println("读取图标失败:", err)
		os.Exit(1)
	}
	fmt.Printf("ICO: %d 字节\n", len(ico))
	if img, w, h, err := tray.IconFromICO(ico); err != nil {
		fmt.Println("解析 ICO 失败:", err)
		os.Exit(1)
	} else {
		fmt.Printf("ICO 解析成功: 选中 %dx%d, %d 字节\n", w, h, len(img))
	}

	t, err := tray.Start(tray.Options{
		IconICO:    ico,
		Title:      "HydroLink Probe",
		Logf:       func(f string, a ...any) { fmt.Printf("log: "+f+"\n", a...) },
		OnActivate: func() { fmt.Println("回调: 左键点击") },
		OnQuit:     func() { fmt.Println("回调: 退出") },
	})
	if err != nil {
		fmt.Println("tray.Start 失败:", err)
		os.Exit(2)
	}
	fmt.Printf("托盘已注册，显示 %d 秒（右键试试菜单）\n", *secs)

	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt, syscall.SIGTERM)
	select {
	case <-ch:
	case <-time.After(time.Duration(*secs) * time.Second):
	}
	t.Stop()
	fmt.Println("已移除，done")
}
