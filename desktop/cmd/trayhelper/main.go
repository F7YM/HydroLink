package main

import (
	"bufio"
	_ "embed"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"hydrolink-desktop/internal/tray"
)

//go:embed appicon.png
var defaultIcon []byte

func main() {
	iconPath := flag.String("icon", "", "path to icon PNG")
	title := flag.String("title", "HydroLink", "tray title")
	tooltip := flag.String("tooltip", "HydroLink — 桌面服务端", "tray tooltip")
	flag.Parse()

	var pngData []byte
	if *iconPath != "" {
		data, err := os.ReadFile(*iconPath)
		if err == nil && len(data) > 0 {
			pngData = data
		}
	}
	if len(pngData) == 0 {
		pngData = defaultIcon
	}
	if len(pngData) == 0 {
		fmt.Fprintf(os.Stderr, "trayhelper: 没有可用的托盘图标\n")
		os.Exit(1)
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

	t, err := tray.Start(tray.Options{
		IconPNG: pngData,
		Title:   *title,
		Tooltip: *tooltip,
		Logf: func(format string, args ...any) {
			fmt.Fprintf(os.Stderr, "[TrayHelper] "+format+"\n", args...)
		},
		OnActivate: func() {
			fmt.Println("ACTIVATE")
		},
		OnQuit: func() {
			fmt.Println("QUIT")
		},
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "[TrayHelper] 注册托盘失败: %v\n", err)
		os.Exit(1)
	}
	defer t.Stop()

	// 通知宿主就绪
	fmt.Println("READY")

	// 监听父进程退出或输入
	done := make(chan struct{})
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		for scanner.Scan() {
			text := scanner.Text()
			if text == "QUIT" {
				break
			}
		}
		close(done)
	}()

	select {
	case <-sigCh:
	case <-done:
	}
}
