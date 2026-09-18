# 协议参考（路由 / 字段 / 上下文 / 版本记录）

给"要照着实现一个宿主"或"要精确对齐字段"的人看：角色定位、运行环境、HTTP 路由与报文、
ABI 上下文全字段、版本记录。**日常写插件不需要通读这一篇。**

## 角色定位

| 名称 | 是什么 | 谁实现 |
|---|---|---|
| **HydroLink** | 产品：手表(vela) + 桌面/手机(server) 互联系统 | 本项目 |
| **HydroKit** | 插件平台：运行时 + 插件 API + HTTP 路由 | 桌面 Go（当前）/ 安卓 Kotlin（后续） |
| **HydroPlugin** | 可分发的插件包：一个 zip，内含 `server.js` 等 | 插件作者 |

核心设计：

- 插件逻辑 = **`server.js`**（纯 JavaScript，写一次，跨平台复用）
- 插件运行在**桌面端 WebView 里的 WebWorker**（专线程；WebView 是桌面 Wails 与安卓 App 都有的组件，这是跨平台复用的基础）
- **WASM 是可选能力**，不是必需运行时
- 手表端（Vela）**不加载任何插件代码**，只通过 HTTP 消费插件生成的组件树——插件作者不需要写手表端 UI

## 运行环境

### server.js 在哪里跑

```
HydroLink 桌面应用（Wails WebView）
└── shell.html（应用内置的"空壳"页：只负责创建 Worker 与展示 UI）
    └── WebWorker：每个已安装插件一个专属 Worker，运行该插件的 server.js
```

- 语言：标准 JavaScript（WebView 内核，Chromium/WebKit）
- **可用**：`async/await`、`Promise`、`WebAssembly`（可选 WASM）、Kit 提供的 API（见 §6、§7）
- **不可用**：DOM、Node.js 核心模块（`fs`/`http`/`net`/`os`…）、`localStorage`（存储用 Kit 的 `storage` API，见 §6）
- **网络**：插件可 `fetch` 外网与本机服务（受 §7 权限约束）；对外服务所需的"被调用"通道由 Kit 提供（见 §5），插件无需自建监听
- **生命周期**：随应用运行；**按需唤起**——应用启动时创建全部已安装插件的 Worker；若某插件 Worker 未运行而请求到达，Kit 通知宿主拉起它（本次请求返回"Worker 离线"，重试即成功）
- **headless 限制**：`cmd/hydrolink`（无 GUI）没有 WebView，不运行 JS 插件（注册仍生效、请求返回离线错误；所有插件目前均为 JS 形态）

### 文档就是运行环境约定

所有插件代码都假定运行在上述环境。任何超出环境的能力（文件系统、任意端口监听、原生 API）都不在兼容范围内。

## HTTP 路由与报文（宿主实现参考）

所有接口都是普通 HTTP（没有 WebSocket），默认端口 **9000**。手表直连宿主时用这些路由；
不便于联网时手表可走互联通道（手机端），报文语义一致。

### 客户端 → 宿主（手表容器用）

| 路由 | 说明 |
|---|---|
| `GET /api/hydro/list` | 插件清单：`{plugins:[{package:{id,name,version},apps:[…],widgets:[…]}]}` |
| `GET|POST /api/hydro/{pkg}/{surface}/{item}/view` | 取视图；`surface` ∈ `app`/`widget`，`item` 是清单里的条目 id |
| `GET /api/hydro/{pkg}/{surface}/{item}/refresh` | 周期刷新（`refreshMs` 到点后容器调它） |
| `POST /api/hydro/{pkg}/{surface}/{item}/event` | 用户事件：`{"event":{"handler":…,"source":…,"value":…},"params":{…}}` |
| `GET /api/hydro/webui/{pkg}/{path}` | 插件 WebUI 静态资源（包内 `web/`） |
| `GET /api/music/art` | 当前封面（`?size=&blur=&dim=&w=&h=`，见能力一章） |

请求可带屏幕参数（`?shape=circle&width=466&height=466&dpr=2&dt=watch`）与设备侧存储快照
（头 `X-Hydro-Store`；无头的传输通道走 `store` query 或请求体）。**这三种 store 通道语义等价**，
宿主按 请求头 → query → body 的顺序取第一个可用的。

响应：`200` 树 JSON；`204` 表示"这个表面没有内容"（`view()` 返回 `null`）；
`400` 参数/路径不合法；`403` 权限不足；`404` 未知插件/条目，或 worker 离线（响应体里写明原因）。

### 宿主 → worker（宿主实现内部，插件无需感知）

| 路由 | 说明 |
|---|---|
| `GET /api/hydro/worker/host?file=worker.html\|worker.js` | 宿主页与胶水（白名单，只允许这两个文件） |
| `GET /api/hydro/worker/poll?package=` | worker 长轮询领取请求（25s 窗口，200 `{}` 表示这一轮没请求） |
| `POST /api/hydro/worker/respond` | worker 回传结果 `{package,id,result}` |
| `POST /api/hydro/worker/context` | WASM 插件取 ABI 上下文（权限门控） |
| `POST /api/hydro/worker/{store,system,notify,music,lock,commands}` | `kit.*` 能力端点（按清单权限门控） |
| `GET /api/hydro/worker/asset?package=&path=` | 插件包内文件（防目录逃逸） |

两种宿主都用**隐藏 WebView** 跑插件：页面加载宿主页路由拿到胶水，胶水长轮询取请求、
调用插件代码（JS 直接执行 / WASM 走 ABI v1）、把结果 `respond` 回去。一个插件一个 WebView，
互不干扰；渲染进程被系统杀掉时宿主会重建它。

### 多屏适配

- 每个 `/api/hydro` 请求携带屏幕信息（query 参数：`shape/width/height/dpr/dt`），插件在 `req.screen` 里读取
- 请求还带 `req.host`：宿主平台（`os` ∈ `linux`/`windows`/`darwin`/`android`、`arch`、`app` 版本，安卓另有 `sdk`）。
  插件按平台分支就靠它，不必嗅探 UA；WASM 插件的 ABI 上下文里另有一份顶层 `ctx.host`
- 节点 `media` 覆盖块：`{ "style": {...}, "media": { "<shape>": {...} } }`，容器按当前形状合并（shape 键：`circle`/`rect`/`pill`，后写覆盖先写）
- 写法参考：demo 的根节点 `media.circle.padding=24`、`media.pill.padding=12`

## 版本记录

- v0.4.5（2026-09-18）：**插件可获知宿主平台**——请求新增 `req.host`（`os`/`arch`/`app`，安卓另有 `sdk`）：
  桌面端 `os` 取 `runtime.GOOS`（linux/windows/darwin），安卓端为 `android`；WASM 的 ABI 上下文
  另有一份顶层 `ctx.host`。此前 JS 插件只能嗅探 userAgent，WASM 插件完全拿不到。
- v0.4.4（2026-09-18）：**封面链在安卓真机验证通过**（`artUrl`/`artBlur` 是局域网地址、
  `/api/music/art` 出图、插件视图里确实用上了封面）；补齐 `w/h` 只在配合
  `size`/`blur`/`dim` 时生效这条规则的**两端测试**（桌面在 handler 里判断、安卓在路由里，
  条件必须一致）。
- v0.4.3（2026-09-18）：**安卓端两处真机才暴露的传输 bug** ——
  ① 宿主页 `script src` 写成相对路径导致胶水不执行（worker 永远离线、手表显示
  "加载失败，请检查服务端"）；② NanoHTTPD 对不带 charset 的请求体按 US-ASCII
  解码，中文/emoji 全变 U+FFFD（服务端统一补 `charset=utf-8`，两端协议不变）。
  另：安卓主界面新增底部「插件」Tab。
- v0.4.2（2026-09-13）：**封面能力补齐到安卓端**（§7/§9.1）——`GET /api/music/art`
  与 ABI 上下文里的 `music.artUrl` / `music.artBlur` 在安卓端落地（此前 `artUrl`
  恒为空串、也没有封面端点，示例插件 deskbuddy 在手表上的背景图因此出不来）；
  几何取整与桌面 `imaging.Fit` 对齐（`466x349`，两端各有测试钉住）。
- v0.4.1（2026-09-13）：**存储快照三条传输通道**（§9.1）——手表经互联通道（XMS Wearable）
  发来的 hydro 请求带不了自定义请求头，两端服务端改为按 `X-Hydro-Store` 头 → `store`
  查询参数 → 请求体 `store` 字段的顺序读取；安卓端 `HttpServer.handleInternalRequest`
  把 `/api/hydro/*` 也交给 HydroKit（此前互联通道直接 404），手表容器 `deviceService.hydro`
  在互联通道下把快照塞进 query/body。两端各加回归测试。
- v0.4.0（2026-09-13）：**安卓端实现落地**（§9.1）——Kotlin 端的 HydroKit 运行时、
  插件包仓库、HTTP 路由、worker 长轮询通道、JS/WASM 宿主（隐藏 WebView）、
  kit 能力与权限门控、插件 WebUI 与管理界面；与桌面端同一套 `.hpk` 与协议，
  跨端字段契约由 `ProtocolContractTest` 钉住。
- v0.3.4（2026-09-06）：§9 **WebUI 标题与文案约定**（标题=插件名，不出现 `WebUI`/包 id；保存成功只提示"保存成功"）；桌搭/天气示例页按此调整。

- v0.1（草案，2026-09-06）：首版规范。与桌面 HydroKit Go 实现同步演进；实现与本文档冲突时以本文档为准（需评审修订）。
- v0.1.1（2026-09-06）：§5 通道实现定为**长轮询**（poll/respond，无 WebSocket 依赖）；§2 生命周期补充"启动即建 + 按需唤起"。
- v0.2（2026-09-06）：**watch 端存储落地**（§6 请求上下文语义：`X-Hydro-Store` 上送快照 / `view.storage` 落盘，容器与 bundle 已实现）；**插件 WebUI 落地**（§9：`web/` 同源服务 + 桌面端遮罩层内嵌）；示例插件更新为 v0.2（名字=watch 端、备注=server 端 + WebUI 设置页）。
- v0.2.6（2026-09-06）：**Widget 表面落地**（手表呈现）——HydroApps 启动器新增"组件"条目，hydro 容器以 `surface=widget` 渲染并轮询 `refreshMs`；示例组件视图补导航节点。
- v0.3.3（2026-09-06）：容器**绝对定位上提到包装层**（修复弧线/浮层在叶子组件内不渲染的根因）；`progress` 角度单位（deg）说明；明确 3 层层级与叶子白名单；桌搭插件侧条改为纯流式布局（直条）。
- v0.3.2（2026-09-06）：示例插件的**单元测试与打包脚本**（`pack.sh`，纯函数 ABI 可直接 cargo test）；桌搭插件补齐调参 WebUI 与自适应几何（见 §9/§8）。
- v0.3.1（2026-09-06）：**`progress` 叶子与全屏背景层**——新增 `type:"progress"`（horizontal/arc，§4）；根节点的 background* 样式提升到整页容器（全屏背景，避开安全区限制）。
- v0.3（2026-09-06）：**WASM 插件（一等公民）**——清单 `runtime:"wasm"` + `entry:*.wasm`；ABI v1（JSON over linear memory 纯函数 + effects 列表，§8）；宿主 WorkerBundle 增加 WASM 加载与调用路径；上下文端点 `/api/hydro/worker/context`（权限门控）；Rust 夹具与 node 端到端测试；§8 重写。
- v0.2.7（2026-09-06）：**Widget 卡片化**——按反馈迭代：组件入口从启动器行改为**设备信息页卡片区**（音乐卡下方独立卡片，卡片不跳转；`button action:'open'` 可跳转 HydroApp；widget 树定义"卡片树"白名单，禁用 nav/scroll/input/switch）；启动器只列应用；示例对齐（v0.2.4）。
- v0.2.5（2026-09-06）：文档明确 item id 形式（URL 安全 slug，仅约定不校验）；桌面插件列表移除安装时间展示。
- v0.2.3（2026-09-06）：**页面导航进协议**——新增 `type:"nav"` 节点（back/open），容器移除硬编码返回栏，插件声明自己的导航（§4）；天气示例带头声明。
- v0.2.2（2026-09-06）：滚动改为**插件声明**——容器移除整页滚动，HydroApp 根节点需为 `type:"scroll"`（§4）；示例天气根节点同步更新。
- v0.2.1（2026-09-06）：示例组织调整——删除 greeting 示例；**天气示例从 Go 内嵌改为 JS 插件包**（`examples/com.demo.weather.hpk`，含 WebUI），内置插件移除（桌面端无内置插件，全部经安装流程；示例中"城市=服务端/省电模式=手表端"展示双端存储）。
