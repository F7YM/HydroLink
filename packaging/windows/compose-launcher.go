package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

var (
	modUser32       = syscall.NewLazyDLL("user32.dll")
	procMessageBoxW = modUser32.NewProc("MessageBoxW")
)

const (
	mbOK            = 0x00000000
	mbIconError     = 0x00000010
	mbIconWarning   = 0x00000030
	mbSetForeground = 0x00010000
	mbTopmost       = 0x00040000
)

func showMessage(title, msg string, flags uint32) {
	t, err1 := syscall.UTF16PtrFromString(title)
	m, err2 := syscall.UTF16PtrFromString(msg)
	if err1 != nil || err2 != nil {
		return
	}
	procMessageBoxW.Call(
		0,
		uintptr(unsafe.Pointer(m)),
		uintptr(unsafe.Pointer(t)),
		uintptr(flags|mbSetForeground|mbTopmost),
	)
}

func fatalDialog(title, msg string) {
	showMessage(title, msg, mbOK|mbIconError)
}

func findJava(appDir string) string {
	// 1. 检查应用根目录内嵌 runtime (runtime/bin/javaw.exe)
	if p := filepath.Join(appDir, "runtime", "bin", "javaw.exe"); fileExists(p) {
		return p
	}
	// 2. 检查 lib 目录下内嵌 runtime (lib/runtime/bin/javaw.exe)
	if p := filepath.Join(appDir, "lib", "runtime", "bin", "javaw.exe"); fileExists(p) {
		return p
	}
	// 3. 检查系统环境变量 JAVA_HOME / JDK_HOME
	if jHome := os.Getenv("JAVA_HOME"); jHome != "" {
		if p := filepath.Join(jHome, "bin", "javaw.exe"); fileExists(p) {
			return p
		}
		if p := filepath.Join(jHome, "bin", "java.exe"); fileExists(p) {
			return p
		}
	}
	if jdkHome := os.Getenv("JDK_HOME"); jdkHome != "" {
		if p := filepath.Join(jdkHome, "bin", "javaw.exe"); fileExists(p) {
			return p
		}
		if p := filepath.Join(jdkHome, "bin", "java.exe"); fileExists(p) {
			return p
		}
	}
	// 4. 检查 PATH 环境变量
	if p, err := exec.LookPath("javaw.exe"); err == nil {
		return p
	}
	if p, err := exec.LookPath("java.exe"); err == nil {
		return p
	}
	return ""
}

func fileExists(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && !fi.IsDir()
}

func getLogPath() string {
	appData := os.Getenv("APPDATA")
	if appData == "" {
		appData = os.TempDir()
	}
	dir := filepath.Join(appData, "hydrolink")
	_ = os.MkdirAll(dir, 0755)
	return filepath.Join(dir, "launcher.log")
}

func readLastLines(filePath string, maxLines int) string {
	f, err := os.Open(filePath)
	if err != nil {
		return ""
	}
	defer f.Close()

	var lines []string
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
		if len(lines) > maxLines*2 {
			lines = lines[len(lines)-maxLines:]
		}
	}
	if len(lines) > maxLines {
		lines = lines[len(lines)-maxLines:]
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}

func main() {
	exePath, err := os.Executable()
	if err != nil {
		exePath = "."
	}
	appDir := filepath.Dir(exePath)

	// 1. 寻找 Java 运行环境
	javaExe := findJava(appDir)
	if javaExe == "" {
		fatalDialog(
			"HydroLink 启动提示",
			"未检测到 Java 运行环境（javaw.exe）。\n\n"+
				"HydroLink 桌面端需要 Java 21+ 运行环境支持。\n"+
				"请确认应用安装完整（包含 runtime 运行库），\n"+
				"或在电脑上安装 Java 21 (如 Eclipse Temurin / Adoptium / Oracle JDK) 后重新运行。",
		)
		os.Exit(1)
	}

	// 2. 拼接 classpath 与应用资源目录
	cp := filepath.Join(appDir, "lib", "app", "*")
	resourcesDir := filepath.Join(appDir, "lib", "app", "resources")
	skikoDir := filepath.Join(appDir, "lib", "app")

	// 3. 构造 JVM 参数
	args := []string{
		"-Djpackage.app-version=1.2.3",
		"-Dcompose.application.configure.swing.globals=true",
		"-Dcompose.application.resources.dir=" + resourcesDir,
		"-Dskiko.library.path=" + skikoDir,
		"--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
		"--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
		"--enable-native-access=ALL-UNNAMED",
		"-Djava.net.preferIPv4Stack=true",
		"-Dfile.encoding=UTF-8",
		"-cp", cp,
		"com.hydro.link.compose.MainKt",
	}

	// 传递外界启动参数（如 --autostart 或特定选项）
	if len(os.Args) > 1 {
		args = append(args, os.Args[1:]...)
	}

	// 4. 重定向启动输出到 launcher.log
	logPath := getLogPath()
	logFile, logErr := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	if logErr == nil {
		fmt.Fprintf(logFile, "=== HydroLink Launcher Started: %s ===\n", time.Now().Format("2006-01-02 15:04:05"))
		fmt.Fprintf(logFile, "App Dir: %s\n", appDir)
		fmt.Fprintf(logFile, "Java Executable: %s\n", javaExe)
		fmt.Fprintf(logFile, "Args: %v\n\n", args)
	}

	cmd := exec.Command(javaExe, args...)
	cmd.Dir = appDir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if logErr == nil {
		cmd.Stdout = logFile
		cmd.Stderr = logFile
	}

	if err := cmd.Start(); err != nil {
		if logErr == nil {
			fmt.Fprintf(logFile, "Failed to start Java: %v\n", err)
			_ = logFile.Close()
		}
		fatalDialog(
			"HydroLink 启动失败",
			fmt.Sprintf("无法启动 Java 进程：\n%v\n\n可执行文件: %s", err, javaExe),
		)
		os.Exit(1)
	}

	// 5. 监听进程在前 2 秒内的退出状态
	done := make(chan error, 1)
	go func() {
		done <- cmd.Wait()
	}()

	select {
	case err := <-done:
		if logErr == nil {
			fmt.Fprintf(logFile, "\nProcess exited early with error: %v\n", err)
			_ = logFile.Close()
		}
		snippet := readLastLines(logPath, 15)
		if snippet != "" {
			snippet = "\n\n错误信息：\n" + snippet
		}
		fatalDialog(
			"HydroLink 启动异常",
			fmt.Sprintf("主程序在启动过程中退出：%v%s\n\n详细日志请查看：\n%s", err, snippet, logPath),
		)
		os.Exit(1)
	case <-time.After(2 * time.Second):
		// 超过 2 秒说明 JVM 启动平稳，已接管运行
		if logErr == nil {
			fmt.Fprintf(logFile, "\nProcess running smoothly after 2 seconds, launcher exiting.\n")
			_ = logFile.Close()
		}
		os.Exit(0)
	}
}
