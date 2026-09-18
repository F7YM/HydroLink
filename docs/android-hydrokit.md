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
| M3 | HTTP 路由 | `HydroKitRoutes.kt`：`/api/hydro/list`、`/api/hydro/{pkg}/{surface}/{item}/{view,refresh,event}`（含存储快照、`?shape&width&height&dpr&dt`、204/400/404/405 语义）、`/api/hydro/webui/...` 静态资源；已接进 NanoHTTPD 的 `serve()` **与互联通道 `handleInternalRequest()`** | ✅ 完成 |
| M4 | Worker 通道 | `WorkerBridge.kt`（长轮询/respond/超时/worker 离线快速失败+唤醒）+ `WorkerPlugin`（Runtime.Plugin ↔ worker）+ `WorkerRoutes`；kit 能力端点（store/context/system/notify/music/lock/commands/asset）留到 M6 | ✅ 完成 |
| M5 | JS/WASM 宿主 | `assets/hydrokit/{worker.html,worker.js}`（移植桌面胶水，JS + WASM ABI v1）+ `PluginHostManager`（每插件一个隐藏 WebView）+ 同源宿主页路由 `HostPageRoutes` | ✅ 完成 |
| M6 | 权限门控 + kit 能力 | `KitRoutes`（store/context/system/notify/music/lock/commands/asset，全部按 manifest 权限门控）+ `AndroidKitCapabilities`（接到通知/音乐/锁屏/命令/系统信息）+ 封面 `GET /api/music/art` 与 ABI 上下文的 `artUrl`/`artBlur`（`MusicArt.kt`：纯像素处理 + 路由，均有单测） | ✅ 完成 |
| M7 | 插件 WebUI | `PluginWebUiActivity`：WebView 打开 `http://127.0.0.1:<port>/api/hydro/webui/<pkg>/index.html`（**同源**，页面直接调 kit），插件作者无需为安卓写第二套页面 | ✅ 完成 |
| M8 | 安卓 UI 接入 | 主界面底部**「插件」Tab**（列表 + 安装 `.hpk` + 卸载二次确认 + 打开界面 + worker 在线状态 + 摘要）与设置里的 `PluginsActivity`（共用 `ui/PluginUi.kt` 一份实现）、服务启动即 `startHosts()`、worker console 与应用日志**同时进 logcat**（`adb logcat -s HydroLink`） | ✅ 完成 |
| M9 | 文档与回归 | 更新 hydroplugin.md 的平台差异说明（已写：§9.1 差异表 + 三条存储快照通道）；跨端同一 `.hpk` 的安装/调用回归（已写：真产物 e2e + 真包解析）；剩余：**真机验证清单** | 🔶 部分完成 |

## 已完成部分（M1 / M2）的验证

```bash
cd android
GRADLE_USER_HOME=/home/float/.gradle \
  /home/float/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest
```

- **126 个 JVM 单测全绿**：`ManifestTest` 7、`RuntimeTest` 13、`PluginStoreTest` 19、
  `HydroKitRoutesTest` 18、`WorkerBridgeTest` 11、`WorkerPluginTest` 6、
  `KitRoutesTest` 12（权限门控/存储/ABI 上下文/资产二进制）、
  `ProtocolContractTest` 7（跨端字段契约）、`HydroInterconnectTest` 6（互联通道报文解析）、
  `HostPageRoutesTest` 4（宿主页 script src 按浏览器规则解析）、
  `RequestBodyCharsetTest` 4（NanoHTTPD 请求体字符集）、
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

## 真机验证记录（Android 16 / OnePlus PLQ110）

2026-09-18 用 `com.hydro.deskbuddy` v0.7.1（WASM）+ `adb forward` **在设备上实测**过：

| 项 | 结果 |
|---|---|
| 插件注册 / 宿主 / worker | `已注册插件` → `worker 宿主已加载` → `worker connected` → `worker ready … (wasm)` |
| 界面树（466×466 circle dpr2） | 200，1034B，`refreshMs:3000`，**0 个乱码** |
| 事件请求 | 200 |
| 长轮询存活（灭屏 75s 后） | 3 次请求 0.75~0.83s 全部 200（隐藏 WebView 没被后台冻结） |
| 插件存储往返（故意不带 charset 的 POST） | 中文原样返回 |
| WebUI 静态页 | `/api/hydro/webui/<pkg>/index.html` → 200 text/html |
| 权限门控 | 未声明的能力一律 403「权限不足: xxx」 |
| 宿主页白名单 | `file=../…` / 未知文件 → 400「不允许的宿主文件」 |
| 封面链 | 上下文里 `artUrl`/`artBlur` 是局域网地址；`/api/music/art` → 200 PNG 363×363；`?size=466&blur=26&dim=0.3` → JPEG（源图小于 466 不放大）；加 `w/h` → 466×466 cover；同参数第二次命中缓存 4ms |
| 插件真的用了封面 | 视图里 `backgroundImage = http://<lan-ip>:9000/api/music/art?w=466&h=466&…` |

**还没在设备上验证**：手表侧渲染（需要手表）、手表只走互联通道的路径、
6 分钟级后台静置（测到一半设备 USB 掉了，需重测）。

## 容易踩的坑（已在代码/测试里写明）

1. **两种 manifest 形状**：包内 `manifest.json` 是扁平的（`{"id":…}`，对应桌面
   `store.PluginManifest`）；协议里的清单是嵌套的（`{"package":{"id":…}}`，对应
   `hydrokit.Manifest`）。混用会报 "缺少 package.id"。
2. **Kotlin 块注释可嵌套**：注释里出现「斜杠+星号」会把后面整段代码注释掉。
3. 版本比较需与桌面一致：缺失段按 0（`1.0` == `1.0.0`）、预发布低于正式
   （`1.0.0-beta` < `1.0.0`）。
4. 安装必须先解压到 `plugins/` 内的临时目录再整体替换，避免半成品；
   同时拒绝 `../` 之类的越界条目。
5. **通知权限是"静默失败"陷阱**：Android 13+ 未授予 POST_NOTIFICATIONS 时
   `NotificationManager.notify()` 不抛异常、也不弹通知，插件会以为成功。
   `AndroidKitCapabilities.notify` 先查 `areNotificationsEnabled()` 再发，
   未授权直接返回 false（插件拿到 `{"status":"error"}`）；`MainActivity`
   首次进入主动申请一次，状态页新增「通知权限」卡片可跳系统设置。
6. **隐藏 WebView 的"两个必须"**：
   · `WebViewClient.onRenderProcessGone` **必须返回 true**——渲染进程被系统杀掉
     （内存压力很常见）时返回 false 会把整个 App 进程一起带走；返回 true 后
     销毁死掉的 WebView、断开 worker、3 秒后重建。
   · 宿主页里的胶水拿的是**绝对地址**，宿主必须在服务端口变化时重建，否则会一直
     打没人听的端口（表现为"列表显示在线但界面不刷新"）；`PluginHostManager.ensure`
     比较 `baseUrl` 决定是否重建。
   · `hosts` 用 `ConcurrentHashMap`：`ensure` 可能来自 HTTP 工作线程（离线唤醒），
     `destroy` 来自主线程。
7. **宿主页的 `script src` 必须指向宿主页路由**：宿主页地址是
   `/api/hydro/worker/host?file=worker.html&…`，浏览器按当前 URL 的目录解析相对
   路径，所以 `<script src="worker.js">` 会变成 `/api/hydro/worker/worker.js` ——
   那条路由不存在，胶水一行不执行，表现为"宿主页加载成功、worker 永远离线、手表上
   显示『加载失败，请检查服务端』"。自动化测试当时是绿的，因为 Node 端 e2e 用
   `globalThis.__HK_CONFIG` 直接跑胶水，根本不经过 HTML 的 script 标签；
   现在由 `HostPageRoutesTest` 按浏览器的解析规则钉住。
8. **NanoHTTPD 对没有 charset 的请求体按 US-ASCII 解码**：`Content-Type:
   application/json`（不带 charset）的 POST 体会被逐字节按 ASCII 解码，中文/emoji
   全变 U+FFFD —— 真机表现是"插件里的歌名是一串方块、事件里输入的中文存进插件
   存储也是方块"。修复在服务端：`HydroHttpServer.normalizeRequestBodyCharset()`
   在 `parseBody()` 之前给缺 charset 的请求补上 `charset=utf-8`（客户端也顺手带了，
   但那只是治标——手表容器和第三方插件都可能不带）。回归见
   `RequestBodyCharsetTest`（起真 NanoHTTPD，同时钉住"不补就会坏"）。
9. **封面几何必须和桌面逐像素对齐**：桌面用 `imaging.Fit`（**截断**取整），
   安卓一开始用四舍五入，同一个 1200×900 封面在两端出来是 466×350 / 466×349。
   两端现在各有一条测试钉住 `466x349`（`MusicArtProcessorTest.watchBackgroundRecipeMatchesDesktopQuery`
   与 `TestProcessArtFitGeometry`），改一边不改另一边就会红。
   `dim` 同样对齐桌面的截断语义（Go 的 `uint8(float64(v)*dim)`）。
10. **`/api/music/art` 故意不做设备信任校验**：手表渲染背景图是渲染器直接取 URL，
   带不了 `X-Device-Id`；桌面端一直如此。安卓端保持一致的公开行为。
11. **互联通道没有自定义请求头**：手表经 XMS Wearable 转发过来的请求是 UDP 报文，
   只有 method / path / body，`X-Hydro-Store` 传不进来。所以 `HttpServer.
   handleInternalRequest` 也要把 `/api/hydro` 插件路由交给 `HydroKit.handle()`
   （否则互联通道下插件页面直接 404），存储快照则按「请求头 → `store` 查询参数
   → body `store`」的顺序读取，两端一致；路径/query 解析抽成
   `HydroInterconnect`（纯函数，有 JVM 单测）。

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
├── HydroInterconnect.kt           互联通道报文的路径/query 解析（无 Android 依赖）
├── …                              （MusicArt.kt 在 server/ 下：封面像素处理 + /api/music/art 路由）
├── HydroKit.kt                    门面（Android Context：filesDir）
├── AndroidKitCapabilities.kt      能力落到通知/音乐/锁屏/命令/系统信息
└── PluginHostManager.kt           每插件一个隐藏 WebView 宿主

assets/hydrokit/{worker.html,worker.js}   与桌面同协议的宿主胶水
```

## 已接入位置

- `HydroHttpServer.serve()`：`/api/hydro*` 交给 `HydroKit.handle()`（worker 端点优先）。
- `HydroHttpServer.handleInternalRequest()`：**互联通道**（手表经 XMS Wearable 发来的
  UDP 报文）同样把 `/api/hydro*` 交给 `HydroKit.handle()`，这样手表无论走 localhost
  还是互联通道，插件页面/事件行为一致。
- `ServerService.startServer()`：启动时 `hydroKit.loadInstalledPlugins()` 把磁盘插件
  注册进运行时，坏插件跳过并记日志。
- `HydroKit.install/uninstall/hotSwap`：安装/卸载/更新后**热注册**，无需重启 App。

## 后续轮次的切入点

- **M9 文档与回归**（剩下的主要工作）：
  1. `docs/hydroplugin.md` 补"平台差异"一节：桌面 vs 安卓（宿主实现、性能/能力差异、
     通知/锁屏/命令在安卓上的前置条件）。
  2. 跨端回归：同一 `.hpk` 在两端都能装、能出树（桌面已有 Go e2e；安卓已有
     Node 跑真胶水的 e2e），把两者的期望字段对齐写进文档。
  3. 安卓侧权限的实际表现写清楚：`notify` 需 POST_NOTIFICATIONS（已声明，App
     首次进入主动申请；未授权时能力返回 error 而非谎报成功）、
     `lock` 需无障碍服务开关、`commands` 走 Shizuku 或 `sh`、`system` 用 /proc 近似。
- **开机自启（待定，需要你拍板）**：`RECEIVE_BOOT_COMPLETED` 权限声明了，但
  manifest 里**没有对应的 receiver**——手机重启后不打开 App，手表就连不上手机
  （需要用户手动开一次 App 把服务拉起来）。要"重启也能用"就得加一个开机广播
  receiver 起 `ServerService`（服务起来后 HydroKit 插件宿主会一起恢复）。
- **真机验证清单**（需要你在手表/手机上过一遍）：
  · 设置 → 管理插件 → 安装 `com.demo.weather.hpk` → 列表出现且 worker 变在线
  · 手表进 Weather App（JS 插件）→ 应能出界面
  · 安装 `com.hydro.deskbuddy.hpk`（WASM）→ 手表桌搭出钟表/歌名
  · 插件「界面」按钮 → 打开 WebUI，改设置保存 → 手表下次刷新生效
  · **互联通道**：手表不连同一 WiFi、只靠 XMS 互联时，插件 App 仍能出界面、
    设置改动仍在（v0.4.1 修的就是这条：互联通道此前 `/api/hydro` 直接 404、
    存储快照也传不进来）
  · 状态页「通知权限」卡片显示已授权（否则插件 `notify` 返回 error，通知看不到）
  · 出问题先 `adb logcat -s HydroLink`：插件加载/宿主加载/worker 连上/请求路由
    每一步都有一条日志（`worker ready: … (wasm)` 出现才说明插件真的跑起来了）
  · 手机放歌 → 手表桌搭出**封面背景**（deskbuddy 用 `music.artBlur`；需要通知使用权，
    否则 `hasAlbumArt()` 为假，上下文里 `artUrl`/`artBlur` 是空串、插件自己退回纯色背景）
  · 卸载 → 手表启动器里对应条目消失、插件存储仍在（重装后设置还在）
- 每完成一个里程碑：跑 `:app:testDebugUnitTest` + `:app:assembleDebug`，
  提交一次，并更新本文件的里程碑状态。

## 打包

```bash
cd android
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk（排障用，开了 WebView 远程调试）
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk（发布用）
```

release 用仓库里的 `android/app/hydrolink-release.keystore` 签名（`build.gradle` 的
`signingConfigs.release`，别名的指纹 SHA-256 `da555363…`，v2 签名方案 —— v2 从
Android 7.0 起支持，正好是 minSdk 24）。**这个 keystore 从初始提交就在版本库里，
升级必须一直用它**；debug 包是另一套签名，两者不能互相覆盖安装。
