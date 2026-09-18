# 插件 WebUI（设置页）与平台差异

插件可以在电脑/手机端提供一个设置页。它与宿主**同源**，页面里直接请求 `/api/hydro/worker/*`
就能调用 Kit 能力——一份 HTML 两端通用。本章末列出两端的平台差异（宿主实现、能力实际行为）。

插件的 `web/` 目录是它与用户交互的桌面界面——典型用途：**插件设置**、**向 HydroApp 同步信息**（写入存储 → 手表 App 下一次 view/refresh 呈现）。

- **服务**：Kit 以 `GET /api/hydro/webui/{package}/{path}` 同源提供（`index.html` 为默认入口，路径防逃逸、仅限包内 `web/` 目录）；桌面端在插件 Tab 为含 `web/index.html` 的插件提供"界面"按钮，以遮罩层内嵌打开
- **标题**：遮罩层标题显示**插件名**（manifest `name`），不显示 `WebUI` 字样或包 id；插件页自身的 `<h1>` 用同一个插件名（页面名不要重复"设置"等后缀）
- **同源**：WebUI 页面与桌面应用同源（asset server 代理），直接 `fetch('/api/hydro/worker/*')` 即可调用 **kit 能力**（按插件 manifest 权限校验，如 `storage`/`notify`）
- **同步到 HydroApp**：WebUI 写入 `kit.storage`（`scope:'server'`）后，手表 App（插件树的 `view()`）在下次渲染时读取即可生效——桌面写、手表读；手表输入走事件回写（§4/§6）
- **示例**：`com.demo.weather`（随源码分发：`desktop/examples/com.demo.weather.hpk`）的 `web/index.html`——城市设置（服务端存储，保存后手表 App 副标题同步）+ 通知演示
- **反馈文案**：保存成功只提示"保存成功"（短、不带生效时机/实现细节的长句）

## 平台差异（桌面 / 安卓）

两端跑**同一套协议与同一份插件包**（`.hpk`），实现方式不同：

| 方面 | 桌面（Go + Wails） | 安卓（Kotlin + NanoHTTPD） |
|---|---|---|
| 参考实现 | `desktop/internal/hydrokit/` | `android/app/src/main/java/com/hydro/link/server/hydrokit/` |
| HTTP 层 | `net/http` | NanoHTTPD（`fi.iki.elonen`） |
| JSON | `encoding/json` | Gson |
| JS 插件宿主 | WebView 里的 **Blob Worker**（前端按需生成） | 每个插件一个**隐藏 WebView**，加载 `assets/hydrokit/worker.html` |
| 宿主页来源 | 前端 `GetPluginBundle()` 生成 | 服务端同源提供：`GET /api/hydro/worker/host?file=worker.html` |
| WASM 插件 | 同一份 worker 胶水里 `WebAssembly.instantiate`（ABI v1） | 同左（同一份胶水，ABI v1） |
| 插件目录 | `<数据目录>/plugins/<id>/` | `<filesDir>/hydrokit/plugins/<id>/` |
| 插件存储 | `<数据目录>/plugins-storage/<id>.json` | `<filesDir>/hydrokit/plugins-storage/<id>.json` |
| 插件 WebUI | 主界面的遮罩层 iframe | 独立 Activity 的 WebView |
| 能力实现 | playerctl / loginctl / 桌面通知 / `sh -c` | MediaSession / 无障碍锁屏 / NotificationManager / Shizuku 或 `sh` |

**协议层面完全一致**（`/api/hydro/*` 路由、存储快照、屏幕查询参数、
worker poll/respond 报文、`View`/`Node` 字段、权限名）。跨端契约由测试钉住：
`android/app/src/test/java/.../ProtocolContractTest.kt` 逐条断言手表容器真正读取的字段。

封面（`music.artUrl` / `music.artBlur`）两端同一契约：

| 请求 | 语义 |
|---|---|
| `GET /api/music/art` | 原图（桌面回源文件字节；安卓回通知里那张位图，无损 PNG） |
| `?size=466&blur=26&dim=0.3` | 等比缩进 466（只缩不放，取整方式两端一致）、模糊、压暗到 30% —— 示例插件 deskbuddy 拿它当手表背景 |
| `?w=466&h=466` | cover 裁剪到指定尺寸。要和 `size` / `blur` / `dim` 之一**同时出现**才生效：三者都是 0 时走「回原图」捷径、w/h 被忽略（桌面与安卓一致，两端各有测试钉住） |

没有封面时返回 404 JSON，**该端点不做设备信任校验**：手表渲染背景图时会直接取
URL，带不了 `X-Device-Id` 头（桌面端一直如此，安卓端保持一致）。几何取整两端钉在
同一组数字上（`466x349`），任一端改了另一边没跟就会各挂一条测试。

存储快照（§6 请求上下文语义）的传输有**三条通道**，两端服务端都按同一优先级读取，
因为手表并不总能带自定义请求头：

| 通道 | 场景 | 位置 |
|---|---|---|
| 请求头 `X-Hydro-Store` | 手表直连 HTTP（Vela `fetch` 可带 header） | 最高优先级 |
| 查询参数 `store` | 互联通道（XMS Wearable UDP 转发，无自定义头） | 次之 |
| 请求体字段 `store` | `event` 等 POST 请求 | 最后 |

安卓端 `HttpServer.handleInternalRequest` 同样把 `/api/hydro/*` 交给 HydroKit，
手表无论走 localhost 还是互联通道，协议行为一致。

安卓端的能力前置条件（插件作者需要知道）：

| 权限 | 安卓上的实际表现 |
|---|---|
| `notify` | 走通知渠道；Android 13+ 需用户授予「通知」权限（App 首次进入会主动申请，状态页可查看/跳设置）。未授权时 `notify` 返回 `error` —— 系统会静默丢弃通知，如实返回失败比谎报成功更有用 |
| `music` | 依赖 MediaSession / 通知监听（未授权时返回 `hasMedia:false`） |
| `lock` | 需要用户在系统设置里开启无障碍服务，否则返回失败 |
| `commands` | 走 Shizuku（需授权）或 `sh -c`（受应用沙箱限制） |
| `system` | 内存/负载近似值（`/proc/loadavg`），字段口径与桌面一致 |
| `storage` / `fetch` | 与桌面一致（服务端存储 / 网络访问） |
