package api

import (
	"net"
	"net/http"
	"strconv"
	"testing"
	"time"

	"hydrolink-desktop/internal/store"
)

func freePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

func TestAnotherInstanceRunning(t *testing.T) {
	// 1) 我们自己的服务在跑 → true
	st := store.NewIn(t.TempDir())
	srv := New(st)
	port := freePort(t)
	if err := srv.Start("127.0.0.1:" + strconv.Itoa(port)); err != nil {
		t.Fatalf("start: %v", err)
	}
	defer srv.Stop()
	waitReady(t, port)
	if !AnotherInstanceRunning(port) {
		t.Error("自家实例在跑时应判定为 true")
	}

	// 2) 空闲端口 → false
	if AnotherInstanceRunning(freePort(t)) {
		t.Error("没有服务监听时应判定为 false")
	}

	// 3) 别的程序占用（不是 HydroLink）→ false
	other := &http.Server{}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() { _ = other.Serve(ln) }()
	defer other.Close()
	otherPort := ln.Addr().(*net.TCPAddr).Port
	http.HandleFunc("/api/version", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"status":"ok","service":"something-else"}`))
	})
	if AnotherInstanceRunning(otherPort) {
		t.Error("非 HydroLink 服务应判定为 false")
	}
}

func waitReady(t *testing.T, port int) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if AnotherInstanceRunning(port) {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("服务未就绪")
}
