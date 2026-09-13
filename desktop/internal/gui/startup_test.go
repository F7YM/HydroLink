package gui

import (
	"net"
	"strings"
	"testing"

	"hydrolink-desktop/internal/api"
	"hydrolink-desktop/internal/app"
)

// 端口被占用时 StartServer 必须给出人话错误——这正是"第二次启动"路径的兜底：
// 单实例锁没拦住（Wails 未起来/非 GUI 启动）时，用户看到的不该是裸 listen error。
func TestStartServerReportsPortConflict(t *testing.T) {
	srv, st, err := app.NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	defer srv.Stop()

	ln, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port

	a := NewApp(srv, st, port, nil)
	err = a.StartServer()
	if err == nil {
		t.Fatal("端口被占用时 StartServer 应返回错误")
	}
	msg := err.Error()
	for _, want := range []string{"无法监听", "实例在运行", "被其他程序占用"} {
		if !strings.Contains(msg, want) {
			t.Fatalf("错误信息缺少 %q: %q", want, msg)
		}
	}
}

func TestStartServerBindsFreePort(t *testing.T) {
	srv, st, err := app.NewServer()
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	defer srv.Stop()

	ln, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()

	a := NewApp(srv, st, port, nil)
	if err := a.StartServer(); err != nil {
		t.Fatalf("空闲端口应当绑定成功: %v", err)
	}
	if !srv.Started() {
		t.Fatal("StartServer 之后 Started() 应为 true")
	}
	if err := a.StartServer(); err != nil {
		t.Fatalf("重复 StartServer 应幂等: %v", err)
	}
}

func TestBindErrorMessage(t *testing.T) {
	err := api.BindError(9000, errDummy{})
	if !strings.Contains(err.Error(), "端口 9000") {
		t.Fatalf("BindError 应包含端口号: %v", err)
	}
}

type errDummy struct{}

func (errDummy) Error() string { return "address already in use" }

// 回归：托盘"退出"曾经完全无效——Wails 的 Quit() 也会调用 OnBeforeClose，
// 而"关闭=最小化到托盘"总是返回 true，于是 mainWindow.Quit() 永远不执行。
func TestCloseDecision(t *testing.T) {
	cases := []struct {
		quitting    bool
		trayRunning bool
		wantHide    bool
	}{
		{quitting: false, trayRunning: true, wantHide: true},   // 点关闭 → 收进托盘
		{quitting: false, trayRunning: false, wantHide: false}, // 无托盘 → 关闭即退出
		{quitting: true, trayRunning: true, wantHide: false},   // 托盘"退出" → 必须真的退出
		{quitting: true, trayRunning: false, wantHide: false},
	}
	for _, tc := range cases {
		if got := closeDecision(tc.quitting, tc.trayRunning); got != tc.wantHide {
			t.Errorf("closeDecision(quitting=%v, trayRunning=%v) = %v, 期望 %v",
				tc.quitting, tc.trayRunning, got, tc.wantHide)
		}
	}
}
