package applog

import (
	"log"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestInitWritesFile(t *testing.T) {
	dir := t.TempDir()
	if err := Init(dir); err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer Close()
	defer log.SetOutput(os.Stderr)

	log.Printf("hello-file-log")
	// AddLog 走 Stdout()
	if _, err := Stdout().Write([]byte("[12:00:00] app line\n")); err != nil {
		t.Fatalf("write: %v", err)
	}

	data, err := os.ReadFile(filepath.Join(dir, FileName))
	if err != nil {
		t.Fatalf("读取日志文件: %v", err)
	}
	got := string(data)
	for _, want := range []string{"hello-file-log", "app line"} {
		if !strings.Contains(got, want) {
			t.Fatalf("日志文件缺少 %q: %q", want, got)
		}
	}
	if Path() == "" {
		t.Fatal("Path() 应返回日志文件路径")
	}
}

// 超过上限时轮转成 .1，并保留一份历史。
func TestRotate(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, FileName)
	if err := os.WriteFile(target, make([]byte, MaxSize+1), 0o644); err != nil {
		t.Fatalf("准备大文件: %v", err)
	}
	if err := Init(dir); err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer Close()
	defer log.SetOutput(os.Stderr)

	if _, err := os.Stat(target + ".1"); err != nil {
		t.Fatalf("应当生成轮转文件: %v", err)
	}
	info, err := os.Stat(target)
	if err != nil {
		t.Fatalf("新日志文件应存在: %v", err)
	}
	if info.Size() > MaxSize {
		t.Fatalf("轮转后新文件不应超过上限: %d", info.Size())
	}
}

// 数据目录不可写时不能崩：返回错误、日志继续走 stderr，Path() 为空。
func TestInitFailureIsNotFatal(t *testing.T) {
	if err := Init(filepath.Join(t.TempDir(), "不存在的子目录")); err == nil {
		t.Fatal("目录不存在时应返回错误")
	}
	if Path() != "" {
		t.Fatal("未启用文件日志时 Path() 应为空")
	}
	if _, err := Stdout().Write([]byte("still works\n")); err != nil {
		t.Fatalf("降级后仍应可写: %v", err)
	}
}
