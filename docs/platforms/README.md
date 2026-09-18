# 平台实现文档

HydroKit 在两端各有一份实现，**协议一致、宿主实现不同**。这一目录记录各自的实现细节、
平台差异、以及排障用的事实（真机验证记录、已知坑）。

| 文件 | 内容 |
|---|---|
| [desktop.md](desktop.md) | 桌面端（Go + Wails）：三平台构建、功能矩阵、诊断入口、已知限制 |
| [android.md](android.md) | 安卓端（Kotlin）：HydroKit 移植的架构决策、里程碑状态、真机自检脚本、真机验证记录、排查速查、踩坑清单、打包方式 |

插件作者通常**不需要**看这些——除非插件在某个平台上行为不同，
那时先看 [plugin/06-webui.md 的平台差异表](../plugin/06-webui.md)（能力层面的差异），
再看这里的实现细节。

## 一句话差异

| | 桌面端 | 安卓端 |
|---|---|---|
| `req.host.os` | `linux` / `windows` / `darwin` | `android` |
| 插件代码运行在 | 桌面应用进程内的隐藏 WebView | 安卓应用进程内的隐藏 WebView |
| 通知 | 系统通知 | 通知渠道（13+ 需授权，未授权时 return error） |
| 音乐 | playerctl / SMTC / Apple Music | MediaSession + 通知使用权 |
| 锁屏 | loginctl | 无障碍服务 |
| 命令 | `sh -c` | root（`su -c`，可关）或 `sh -c` |
| 封面 | `/api/music/art`（进程内解码） | `/api/music/art`（MediaSession 封面，需通知使用权） |
