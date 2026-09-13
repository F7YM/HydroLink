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

## 真机排查（Windows 崩溃用）

Windows 上若出现"窗口/托盘一起消失"（硬崩，访问违例会直接干掉进程，Go 的
`recover` 拦不住），用自检参数逐项隔离。**控制台版**可直接看输出；GUI 版输出同时
写进 `<数据目录>/hydrolink.log`：

```bat
rem 逐项自检（每项打印"开始/结果"，崩了就停在那一行）
HydroLink-amd64-console.exe -selftest=all
HydroLink-amd64-console.exe -selftest=music,cover      rem 只查音乐/封面
HydroLink-amd64-console.exe -selftest=notify           rem 只查系统通知（beeep → go-toast COM）
HydroLink-amd64-console.exe -selftest=tray             rem 只查托盘
HydroLink-amd64-console.exe -selftest=loop -selftest-times=50   rem 模拟手表轮询 music+cover

rem 二分定位：正常启动但把可疑项关掉
HydroLink-amd64.exe -no-cover          rem 不读 SMTC 缩略图（手写 vtable 那一段）
HydroLink-amd64.exe -no-notify         rem 不发系统通知（beeep→go-toast 手搓 COM）
HydroLink-amd64.exe -no-cover -no-notify
```

真机已知问题与对应修法（Windows，2026-09-13 实测）：

- **通知完全发不出去**：`beeep` → `go-toast` 的 `pushCOM` 调 `ole.RoInitialize(MTA)`
  时线程已被设成 STA → `RPC_E_CHANGED_MODE`；它的失败回退还会起**带控制台窗口**的
  PowerShell（闪黑窗来源之一）。修法：Windows 改用 PowerShell 的 WinRT 投影弹 toast
  （不碰进程内 COM、子进程窗口隐藏），Linux/macOS 继续用 beeep。
- **取封面超时**：在 STA 线程上发起 WinRT 异步操作时，完成回调要投递到该 STA 的
  消息队列，而 Go 不泵消息 → `IAsyncInfo::GetStatus` 永远不是 Completed。
  修法：所有 WinRT 调用固定在一个**常驻专属 MTA 线程**上串行执行
  （`internal/system/winrt_thread_windows.go`）。
- **WinRT 接口是"扁平"的**（重要）：每个接口只继承 `IInspectable`，`requires`
  只表示"实现类还必须实现这些接口"，**基接口方法不会内联进 vtable** —— 基接口
  能力（`IClosable::Close`、`IInputStream::ReadAsync`）必须另外 `QueryInterface`。
  按经典 COM 继承去写会让槽位整体偏移，调用到野函数指针 → 进程**静默终止**
  （无 Go panic 堆栈、无错误输出，表现就是"窗口和托盘一起消失"）。
  权威布局可对照 windows-rs 生成代码里的 `*_Vtbl` 与 `define_interface!` 行
  （IID 一并核对）。守护测试：`internal/system/winrt_layout_windows_test.go`。
- **回调 panic 会杀进程**：非 HTTP handler 的 goroutine 里 panic 不受 net/http
  保护，而 GUI 子系统没有控制台，堆栈写进没人看的 stderr → "窗口和托盘一起消失"。
  修法：托盘回调（`safeCall`）与 Wails 事件回调（`App.safeGo`）加 recover + 记堆栈。

日志里已有的面包屑（崩溃时最后一条就是崩溃点）：

- `music: 当前曲目 "X" — "Y"（播放中=…，封面读取=…）`（切歌时一条）
- `music: 读取封面 start（"X"）` / `music: 读取封面 ok: file://…`
- `发送配对系统通知: <设备名>`
- `tray: 已注册（Windows，hwnd=…）`

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
