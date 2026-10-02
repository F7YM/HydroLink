package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
)

func main() {
	exePath, err := os.Executable()
	if err != nil {
		exePath = "."
	}
	appDir := filepath.Dir(exePath)

	// 1. 寻找 Java 运行环境
	var javaExe string
	// 检查当前目录下是否有 runtime/bin/javaw.exe
	embeddedJava := filepath.Join(appDir, "runtime", "bin", "javaw.exe")
	if _, err := os.Stat(embeddedJava); err == nil {
		javaExe = embeddedJava
	} else if jHome := os.Getenv("JAVA_HOME"); jHome != "" {
		jhJava := filepath.Join(jHome, "bin", "javaw.exe")
		if _, err := os.Stat(jhJava); err == nil {
			javaExe = jhJava
		}
	}
	if javaExe == "" {
		if p, err := exec.LookPath("javaw.exe"); err == nil {
			javaExe = p
		} else if p, err := exec.LookPath("java.exe"); err == nil {
			javaExe = p
		} else {
			javaExe = "javaw.exe"
		}
	}

	// 2. 拼接 classpath
	cp := filepath.Join(appDir, "lib", "app", "*")

	// 3. 构造 JVM 参数
	args := []string{
		"-Dcompose.application.configure.swing.globals=true",
		"-Dcompose.application.resources.dir=" + filepath.Join(appDir, "lib", "app", "resources"),
		"--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
		"--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
		"--enable-native-access=ALL-UNNAMED",
		"-Djava.net.preferIPv4Stack=true",
		"-Dfile.encoding=UTF-8",
		"-cp", cp,
		"com.hydro.link.compose.MainKt",
	}

	cmd := exec.Command(javaExe, args...)
	cmd.Dir = appDir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}

	if err := cmd.Start(); err != nil {
		fmt.Fprintf(os.Stderr, "启动失败: %v\n", err)
		os.Exit(1)
	}
}
