package system

import (
	"bytes"
	"os/exec"
	"runtime"
	"time"
)

// ShellResult carries the outcome of a shell command execution.
type ShellResult struct {
	Success  bool
	ExitCode int
	Output   string
}

// RunCommand executes a command through the platform shell and returns its
// output (stdout+stderr) and exit code. Output is capped by the caller.
func RunCommand(command string) ShellResult {
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		cmd = exec.Command("cmd", "/c", command)
	} else {
		cmd = exec.Command("sh", "-c", command)
	}
	var buf bytes.Buffer
	cmd.Stdout = &buf
	cmd.Stderr = &buf
	// Avoid a hung command blocking the API indefinitely.
	if err := cmd.Start(); err != nil {
		return ShellResult{Success: false, ExitCode: -1, Output: buf.String()}
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		code := 0
		if err != nil {
			if ee, ok := err.(*exec.ExitError); ok {
				code = ee.ExitCode()
			} else {
				code = -1
			}
		}
		return ShellResult{Success: code == 0, ExitCode: code, Output: buf.String()}
	case <-time.After(60 * time.Second):
		_ = cmd.Process.Kill()
		return ShellResult{Success: false, ExitCode: -1, Output: buf.String() + "\n[timeout]"}
	}
}
