# 安卓端 HydroKit 移植计划

> 目标：**给安卓端加上完整 HydroKit** —— 安卓 App 与桌面端同为 WebView 宿主，
> 跑同一套插件包（`.hpk`）、同一套 HTTP 协议、同一份 `server.js` / `plugin.wasm`。
> 协议权威描述见 [hydroplugin.md](hydroplugin.md)；桌面参考实现见
> `desktop/internal/hydrokit/` 与 `desktop/internal/api/{worker,media}.go`。

## 架构决策

| 决策 | 取值 | 理由 |
|---|---|---|
| 语言/框架 | Kotlin + NanoHTTPD + Gson（与现有安卓端一致） | 无需引入新依赖 |
| 协议兼容 | 与桌面端**逐字段**对齐（路由、JSON、头部、错误语义） | 手机与桌面用同一套插件包与同一套手表端请求 |
| JS 插件运行 | **隐藏 WebView** 里跑（每个插件一个宿主页） | 安卓没有 Blob Worker 的进程隔离需求；WebView 里可跑同一份 `server.js` |
| WASM 插件 | WebView 的 `WebAssembly` + 与桌面端同款 ABI v1 | 复用桌面已验证的宿主胶水与 ABI |
| 通信 | 长轮询 `GET /api/hydro/worker/poll` + `POST /respond` | 与桌面一致；不依赖 WebSocket |
| 插件包 | 复用桌面 `manifest.json` 语义（**扁平**） | 同一 `.hpk` 双端可装 |
| 存储 | 服务端存储落 `files/plugins-storage/<id>.json`；设备侧存储随请求头 `X-Hydro-Store` | 同桌面 §6 |
| 依赖注入 | 核心逻辑（清单/树/运行时/仓库）**不依赖 Android API** | 全部可在 JVM 单测里跑，便于验证 |

## 里程碑与状态

| # | 里程碑 | 内容 | 状态 |
|---|---|---|---|
| M1 | 核心模型与注册表 | `Model.kt`（Node/View/Request/Event/Screen/Manifest）、`Runtime.kt`（注册/注销/查询/list/dispatch） | ✅ 完成 |
| M2 | 插件包仓库 | `PluginStore.kt`：安装（zip/顶层目录/路径逃逸防护）、重装/升级/**禁止降级**、卸载保留存储、runtime/entry 判定、插件 KV 存储 | ✅ 完成 |
| M3 | HTTP 路由 | `HydroKitRoutes.kt`：`/api/hydro/list`、`/api/hydro/{pkg}/{surface}/{item}/{view,refresh,event}`（含 `X-Hydro-Store`、`?shape&width&height&dpr&dt`、204/400/404/405 语义）、`/api/hydro/webui/...` 静态资源；已接进 NanoHTTPD | ✅ 完成 |
| M4 | Worker 通道 | `WorkerBridge.kt`（长轮询/respond/超时/worker 离线快速失败+唤醒）+ `WorkerPlugin`（Runtime.Plugin ↔ worker）+ `WorkerRoutes`；kit 能力端点（store/context/system/notify/music/lock/commands/asset）留到 M6 | ✅ 完成 |
| M5 | JS/WASM 宿主 | `assets/hydrokit/{worker.html,worker.js}`（移植桌面胶水，JS + WASM ABI v1）+ `PluginHostManager`（每插件一个隐藏 WebView）+ 同源宿主页路由 `HostPageRoutes` | ✅ 完成 |
| M6 | 权限门控 + kit 能力 | `KitRoutes`（store/context/system/notify/music/lock/commands/asset，全部按 manifest 权限门控）+ `AndroidKitCapabilities`（接到通知/音乐/锁屏/命令/系统信息） | ✅ 完成 |
| M7 | 插件 WebUI | 安卓端打开插件 `web/index.html`（同源指向本机服务端），供插件设置页使用 | ⏳ 待做 |
| M8 | 安卓 UI 接入 | 插件列表（安装/卸载/打开 WebUI）、启动即加载磁盘插件、日志接入现有日志页 | ⏳ 待做 |
| M9 | 文档与回归 | 更新 hydroplugin.md 的平台差异说明；跨端同一 `.hpk` 的安装/调用回归 | ⏳ 待做 |

## 已完成部分（M1 / M2）的验证

```bash
cd android
GRADLE_USER_HOME=/home/float/.gradle \
  /home/float/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest
```

- **88 个 JVM 单测全绿**：`ManifestTest` 7、`RuntimeTest` 13、`PluginStoreTest` 19、
  `HydroKitRoutesTest` 18、`WorkerBridgeTest` 11、`WorkerPluginTest` 6、
  `KitRoutesTest` 12（权限门控/存储/ABI 上下文/资产二进制）、
  `WorkerGlueE2ETest` 2（**真产物 e2e**，见下）。
- **真产物端到端（M5 的关键验证）**：用 Node 执行 **assets 里的真 `worker.js` 胶水**，
  对着**真 NanoHTTPD + HydroKit 路由**（JVM 里起服务）跑完整链路：
  `HTTP view → Runtime → WorkerPlugin → Bridge → worker.js 轮询 → 加载插件 → 回填 View → HTTP 响应`。
  · `com.demo.weather.hpk`（JS，仓库真实发布包）→ 返回真实天气组件树 ✓
  · `com.hydro.deskbuddy.hpk`（**WASM**）→ 返回桌搭树（`refreshMs:3000`）✓
  即：**同一份 .hpk 在安卓实现上真的能跑**，JS 与 WASM 两条路径都通。
- **真实插件包回归**：直接安装仓库里已发布的
  `desktop/examples/com.demo.weather.hpk`（JS）与 `com.hydro.deskbuddy.hpk`（WASM），
  断言 id/name/version/runtime/entry/permissions/`web/index.html` 均正确解出 ——
  证明安卓端能装桌面端同一份 `.hpk`。

## 容易踩的坑（已在代码/测试里写明）

1. **两种 manifest 形状**：包内 `manifest.json` 是扁平的（`{"id":…}`，对应桌面
   `store.PluginManifest`）；协议里的清单是嵌套的（`{"package":{"id":…}}`，对应
   `hydrokit.Manifest`）。混用会报 "缺少 package.id"。
2. **Kotlin 块注释可嵌套**：注释里出现「斜杠+星号」会把后面整段代码注释掉。
3. 版本比较需与桌面一致：缺失段按 0（`1.0` == `1.0.0`）、预发布低于正式
   （`1.0.0-beta` < `1.0.0`）。
4. 安装必须先解压到 `plugins/` 内的临时目录再整体替换，避免半成品；
   同时拒绝 `../` 之类的越界条目。

## 落点（安卓端目录）

```
hydrokit/
├── Model.kt / Runtime.kt          协议模型与注册表（无 Android 依赖）
├── PluginStore.kt                 插件包安装/卸载/存储（无 Android 依赖）
├── HydroKitRoutes.kt              /api/hydro 路由（无 Android 依赖）
├── WorkerBridge.kt                worker 长轮询通道（无 Android 依赖）
├── WorkerPlugin.kt                Runtime.Plugin ↔ worker 适配
├── KitRoutes.kt                   kit 能力端点 + 权限门控（无 Android 依赖）
├── HostPageRoutes.kt              宿主页同源提供（无 Android 依赖）
├── HydroKit.kt                    门面（Android Context：filesDir）
├── AndroidKitCapabilities.kt      能力落到通知/音乐/锁屏/命令/系统信息
└── PluginHostManager.kt           每插件一个隐藏 WebView 宿主

assets/hydrokit/{worker.html,worker.js}   与桌面同协议的宿主胶水
```

## 已接入位置

- `HydroHttpServer.serve()`：`/api/hydro*` 交给 `HydroKit.handle()`（worker 端点优先）。
- `ServerService.startServer()`：启动时 `hydroKit.loadInstalledPlugins()` 把磁盘插件
  注册进运行时，坏插件跳过并记日志。
- `HydroKit.install/uninstall/hotSwap`：安装/卸载/更新后**热注册**，无需重启 App。

## 后续轮次的切入点

- **M7 插件 WebUI**：安卓端需要能打开插件的 `web/index.html`（现在只有
  `HydroKitRoutes` 在服务端提供这些资源）。做法：新增一个 Activity 承载 WebView，
  加载 `http://127.0.0.1:<port>/api/hydro/webui/<pkg>/index.html`（**同源**，页面里
  直接 `fetch('/api/hydro/worker/*')` 即可调用 kit 能力，与桌面端一致）。
- **M8 安卓 UI 接入**：插件列表页（从文件选择器装 `.hpk`、卸载、打开 WebUI、
  显示 worker 状态），并把插件日志并入现有日志页。
- **M9 文档与回归**：`hydroplugin.md` 补平台差异（安卓 vs 桌面）、
  同一 `.hpk` 双端回归、以及"权限在安卓端的实际表现"（通知需 POST_NOTIFICATIONS、
  锁屏需无障碍开关等）。
- 每完成一个里程碑：跑 `:app:testDebugUnitTest` + `:app:assembleDebug`，
  提交一次，并更新本文件的里程碑状态。
