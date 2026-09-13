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
| M5 | JS/WASM 宿主 | 隐藏 WebView + 宿主胶水（移植桌面 `WorkerBundle`/`WasmBundle` 的 JS）；安装/卸载/启动时生命周期；版本变化时重启宿主 | ⏳ **下一步** |
| M6 | 权限门控 | manifest.permissions → kit 能力（storage/notify/music/lock/commands/fetch/system） | ⏳ 待做 |
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

- **74 个 JVM 单测全绿**：`ManifestTest` 7、`RuntimeTest` 13、`PluginStoreTest` 19、
  `HydroKitRoutesTest` 18（HTTP 语义）、`WorkerBridgeTest` 11（长轮询/超时/离线唤醒）、
  `WorkerPluginTest` 6（含进程内端到端：Runtime → WorkerPlugin → Bridge → 假 worker → View）。
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

## 已接入位置

- `HydroHttpServer.serve()`：`/api/hydro*` 交给 `HydroKit.handle()`（worker 端点优先）。
- `ServerService.startServer()`：启动时 `hydroKit.loadInstalledPlugins()` 把磁盘插件
  注册进运行时，坏插件跳过并记日志。
- `HydroKit.install/uninstall/hotSwap`：安装/卸载/更新后**热注册**，无需重启 App。

## 后续轮次的切入点

- **M5（下一步）**：隐藏 WebView 宿主。要点：
  1. 在 `app/src/main/assets/hydrokit/` 放宿主页 `worker.html` + `kit.js`，
     把桌面端 `WorkerBundle`/`WasmBundle` 的 JS 胶水移植过来（同样是
     `fetch(poll) → 调用 server.js / wasm 的 hydro_view → fetch(respond)`）。
  2. 用 `WebView`（`javaScriptEnabled=true`，`addJavascriptInterface` 不需要——
     全部走 fetch 到本机服务端）承载；每个插件一个宿主页实例。
  3. 生命周期：安装/启动时启动宿主；卸载/版本变化时销毁重建；
     收到 `bridge.onNeedWake` 时拉起宿主。
- 每完成一个里程碑：跑 `:app:testDebugUnitTest` + `:app:assembleDebug`，
  提交一次，并更新本文件的里程碑状态。
