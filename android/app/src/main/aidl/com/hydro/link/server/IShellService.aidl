// Shizuku 用户服务（在 Shizuku 以 shell/root 身份启动的进程里执行命令）。
// 返回 "$exitCode\n$output"（首个换行前是返回码）。
package com.hydro.link.server;

interface IShellService {
    String runCommand(String command, int timeoutMs);
}
