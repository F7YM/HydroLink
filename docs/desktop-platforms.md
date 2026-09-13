# HydroLink 桌面端平台支持

> 范围：`desktop/`（Go + Wails v2）。插件规范见 [hydroplugin.md](hydroplugin.md)。
> 更新于 2026-09-13。**本页区分"已验证"与"待真机确认"**，别把没验过的写成能用。

## 构建

| 平台 | 命令 | 产物 |
|---|---|---|
| Linux | `bash build-linux.sh` | `bin/HydroLink`（需 webkit2gtk-4.1） |
| Windows | `./build-windows-cross.sh [amd64\|arm64] [--console]` | `bin/HydroLink-<arch>.exe`（默认 GUI 子系统，无控制台） |
| Windows（本机） | `build-windows.bat` | `bin\HydroLink.exe` |
| macOS | `bash build-macos.sh`（**必须在 Mac 上**，需 Xcode CLT） | `bin/HydroLink` |

- Windows 默认 `-H windowsgui`：双击不会多一个黑窗口。排障要看控制台输出时用
  `--console`（子系统保持 console）。
- macOS **必须**在 macOS 上构建：Wails 的 Cocoa 后端是 cgo，无法交叉编译。
  目前只产出裸二进制（无 `.app` bundle / 签名），Dock 名称与通知归属都不理想。

## 功能矩阵

| 能力 | Linux | Windows | macOS |
|---|---|---|---|
| HTTP 服务 / 配对 / 命令 / 插件（JS+WASM） | ✅ | ✅ | ✅（同构代码） |
| 系统托盘 | ✅ StatusNotifierItem(DBus) | ✅ Shell_NotifyIcon（ICO 优先，缺 ICO 时由 PNG 自绘 HICON） | ❌ 未实现（设置页会提示"托盘不可用"） |
| 音乐读取 / 控制 | ✅ playerctl (MPRIS) | ✅ WinRT SMTC | ⚠️ 仅 Apple Music，状态写死 Playing、无封面 |
| 封面（`/api/music/art`） | ✅ file:// 与 http(s) | ✅ SMTC 缩略图落临时文件 | ❌ 无 artUrl（插件已不再请求封面） |
| 锁屏 | ✅ loginctl/xdg 等 | ✅ rundll32 LockWorkStation | ⚠️ 需辅助功能授权，退路只息屏 |
| 电池 | ✅ /sys | ✅ PowerShell Win32_Battery | ✅ pmset |
| 文件日志 | ✅ | ✅（GUI 子系统唯一可观测通道） | ✅ |
| 单实例 | ✅ | ✅ | ✅（Wails 锁） |
| 托盘开关（设置） | ✅ | ✅ | ❌ 显示"托盘不可用" |

## 诊断

- **日志文件**：`<数据目录>/hydrolink.log`（Linux `~/.config/hydrolink/`、
  Windows `%AppData%\hydrolink\`、macOS `~/Library/Application Support/hydrolink/`）；
  超过 1 MiB 轮转为 `.log.1`。状态页「日志文件」一行显示完整路径（可自行复制打开）。
- **音乐读取失败会写日志**：`/api/music` 查询出错时记一条 `music: …` 警告
  （Windows 上 SMTC 不可用这类情况不再静默）。
- **启动日志含选择的网卡**：`local IP: 192.168.3.48 (iface wlp0s20f3, scored)`。
  若显示的地址手表连不上，先看这里——代理/VPN 的 TUN 网卡（Clash 的 198.18.x）
  会被主动跳过。
- **托盘图标数据**：启动日志记录 `托盘图标数据: PNG … 字节 / ICO … 字节`，
  托盘起不来时先看这一行（设置页会显示"托盘不可用：<原因>"）。

## 已知限制（未做，不是 bug）

- macOS 托盘（`NSStatusItem` 需要 cgo/ObjC）；macOS 无 `.app`/签名/公证。
- macOS 音乐只认 Apple Music，且不提供封面。
- 无头模式（`-no-gui`）**无法服务 WASM 插件**：WASM 跑在 WebView 的 Worker 里，
  没有窗口就没有执行环境（请求会返回 404 worker offline）。要支持需宿主侧运行时。
- Windows arm64 构建不嵌图标资源（只有 `rsrc_windows_amd64.syso`）。
- 首次启动绑定 `0.0.0.0:9000` 时 Windows/macOS 会弹防火墙授权，需放行。

## 验证状态

| 项 | 已验证方式 |
|---|---|
| Linux | 全量 `go test ./...`、真机双实例、托盘注册/菜单/退出（会话总线实测） |
| Windows 编译 | amd64/arm64 交叉编译通过（含 WinRT SMTC 与托盘代码） |
| Windows 运行 | wine 实跑：数据目录=%AppData%、`cmd /c`、gopsutil、日志文件、插件注册/存储、托盘 `NIM_ADD` 与菜单构建、音乐查询在无 SMTC 环境下的优雅降级（has_media=false 且记日志） |
| macOS | 全部内部包 `GOOS=darwin` 类型检查通过；**未在真机运行** |
| 待真机确认 | Windows：托盘图标可见性与点击、真实曲目/封面、黑窗口是否消失；macOS：全部运行行为 |
