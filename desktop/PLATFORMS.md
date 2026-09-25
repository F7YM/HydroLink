# HydroLink 桌面端平台支持

> 范围：`desktop/`（Go + Wails v2）。插件规范见 GitBook 文档站的《协议参考》。
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
| 锁屏 | ✅ loginctl/xdg 等 | ✅ rundll32 LockWorkStation（同样隐藏子进程窗口） | ⚠️ 需辅助功能授权，退路只息屏 |
| 执行命令 | ✅ `sh -c` | ✅ `cmd /c`（`CREATE_NO_WINDOW`，不闪黑窗） | ✅ `sh -c` |
| 电池 | ✅ /sys | ✅ PowerShell Win32_Battery（`CREATE_NO_WINDOW`，不闪黑窗） | ✅ pmset |
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

## 启动可见性（"闪一下就没了"这类问题）

- **窗口必须由 Wails 显示**（`StartHidden` 恒为 false，`main_test.go` 钉住）：
  Wails 在 `onDomReady` 里才 `ShowWindow`，而 `OnStartup` 是并发协程、跑在窗口
  就绪之前 —— 从那里调 `WindowShow` 无效。一旦开启 `StartHidden`，Windows 实现
  会 `if StartHidden { return }` 直接不显示窗口，进程活着但窗口永不出现；托盘
  关掉时毫无入口，只有第二次启动会被单实例通知捞出来（"打开就闪退、第二次才行"）。
  Linux 实现因为 `Hide()` 之后又 `gtk_widget_show_all()` 而看不出这个问题。
- **第二实例不闪窗口**不需要 `StartHidden`：Wails 的 `SetupSingleInstance` 在
  窗口创建之前就 `os.Exit`（Windows/Linux 皆然），并把消息转给在跑的实例
  （`OnSecondInstanceLaunch` → 唤起窗口）。
- **致命错误可见**：GUI 子系统（Windows）与 .desktop 启动（Linux）都看不到
  stdout，`log.Fatalf` 等于"无声消失"。现在统一走 `fatalExit`：写日志 + 弹原生
  错误框（Windows `MessageBoxW`；Linux 有 zenity/kdialog 就用，20s 超时不阻塞）。
- **端口被别的程序占用**不再退出：窗口留着并提示"可在设置里更换端口"。
- **已知边角**：同端口第二次启动时，我们的"已有实例在运行"兜底分支（`os.Exit`）
  可能先于 Wails 的 `SetupSingleInstance` 执行；两条路径都不会显示窗口，行为一致。

## 诊断入口

- **日志文件**：`<数据目录>/hydrolink.log`（Windows `%AppData%\hydrolink\`、
  Linux `~/.config/hydrolink/`、macOS `~/Library/Application Support/hydrolink/`），
  超 1 MiB 轮转为 `.log.1`；状态页「日志文件」一行显示完整路径。
  音乐/通知等平台层只在**出错时**写日志（如 `music: 封面不可用`、`系统通知发送失败`、
  `system: 音乐查询 panic`），不会逐次刷屏。
- **托盘图标数据**：启动日志里 `托盘图标数据: PNG … 字节 / ICO … 字节`，
  托盘起不来时先看这一行（设置页会显示"托盘不可用：<原因>"）。
- **启动网卡**：`local IP: 192.168.3.48 (iface wlp0s20f3, default-route)` ——
  手表连不上时先看这里（代理/VPN 的 TUN 网卡会被主动跳过）。
- **崩溃留痕**：托盘回调与 Wails 事件回调都带 recover，panic 会连堆栈写进日志
  （非 HTTP handler 的 goroutine panic 不受 net/http 保护，曾表现为"窗口与托盘
  一起消失"）。非 Windows 平台若窗口起不来，`fatalExit` 会尝试弹 zenity/kdialog。

### 修过的真机坑（留档，避免重犯）

- **WinRT 接口是"扁平"的**：每个接口只继承 `IInspectable`，`requires` 只表示
  "实现类还要实现这些接口"，**基接口方法不内联进 vtable** —— 基接口能力
  （`IClosable::Close`、`IInputStream::ReadAsync`）必须另外 `QueryInterface`。
  按经典 COM 继承去写会让槽位整体偏移，调用到野函数指针 → 进程**静默终止**。
  权威布局对照 windows-rs 的 `*_Vtbl` 与 `define_interface!` 行；守护测试见
  `internal/system/winrt_layout_windows_test.go`。
- **WinRT 线程模态**：apartment 一旦设定不可更改。在 STA 线程上发起 WinRT 异步
  操作，完成回调要投递到该 STA 的消息队列而 Go 不泵消息 → 异步永远不完成
  （表现为"读取封面超时"）。所以所有 WinRT 调用固定在一个常驻专属 MTA 线程上
  （`internal/system/winrt_thread_windows.go`）。
- **Windows 通知不用进程内 COM**：beeep→go-toast 会在真机上撞公寓模态
  （`RPC_E_CHANGED_MODE`），且其回退会起带控制台窗口的 PowerShell。改用
  PowerShell 的 WinRT 投影（子进程窗口隐藏），并注册 HKCU AppUserModelId
  让通知显示为 HydroLink。
- **GUI 子系统（`-H windowsgui`）没有控制台**：所以日志必须落文件，致命错误要
  弹原生错误框（`fatalExit` → `MessageBoxW`），否则失败表现为"闪一下就没了"。
- **子进程控制台**：查电池的 PowerShell、执行命令的 `cmd /c` 都要
  `CREATE_NO_WINDOW`（`hideWindowAttr()`），否则会闪黑窗。

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
