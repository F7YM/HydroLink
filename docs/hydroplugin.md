# HydroPlugin 插件规范（草案 v0.1）

> 状态：**规范先行**——本文档是 HydroKit 插件系统的权威描述，后续实现按此执行。
> 适用范围：桌面端（HydroLink desktop，Go + Wails）。安卓端为后续里程碑，采用同一规范（同为 WebView 宿主 + 同一插件包），届时补充平台差异说明。

## 1. 角色定位

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

## 2. 运行环境

### 2.1 server.js 在哪里跑

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

### 2.2 文档就是运行环境约定

所有插件代码都假定运行在上述环境。任何超出环境的能力（文件系统、任意端口监听、原生 API）都不在兼容范围内。

## 3. 插件包结构（服务端压缩包）

插件以 zip 分发，**zip 的内容就是插件的全部**：

```
com.demo.weather.hpk                 ← zip 文件（.hpk 后缀，也是 zip）
├── manifest.json                    ← 插件清单（必填）
├── server.js                        ← 插件服务入口（必填，路径可在 manifest 配置）
├── assets/                          ← 可选：随包资源（.wasm、模型、图片、数据）
│   ├── model.wasm
│   └── data.json
└── web/                             ← 可选：插件 WebUI（设置页等；由 Kit 同源服务，
                                         `web/index.html` 为入口，见 §9）
```

### 3.1 manifest.json 字段

```json
{
  "id": "com.demo.weather",
  "name": "示例天气",
  "version": "0.1.0",
  "icon": "assets/icon.png",
  "entry": "server.js",
  "apps":    [{ "id": "main", "name": "天气" }],
  "widgets": [{ "id": "now", "name": "当前天气", "refreshMs": 60000 }],
  "permissions": ["notify", "music", "lock", "commands", "fetch", "storage"]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 包唯一标识（路由命名空间：`/api/hydro/{id}/...`），必须为反向域名式 |
| `name` / `version` / `icon` | string | 展示用 |
| `entry` | string | server.js 路径（默认 `server.js`） |
| `apps` | item[] | HydroApp 注册，`{id, name}` |
| `widgets` | item[] | HydroWidget 注册，`{id, name, refreshMs?}`（refreshMs 无则服务端按 View.refreshMs） |
| `permissions` | string[] | 能力权限声明（§7 权限清单，未声明即不可调用） |

## 4. server.js 编程接口

插件是一个 CommonJS 风格模块，暴露两个函数：

```js
// server.js —— 完整可运行的最小示例
module.exports = {
  // 返回插件清单（与 manifest.json 保持一致；以后可由 kit 自动生成）
  manifest() {
    return {
      package: { id: 'com.demo.weather', name: '示例天气', version: '0.1.0' },
      apps: [{ id: 'main', name: '天气' }],
      widgets: [{ id: 'now', name: '当前天气', refreshMs: 60000 }]
    }
  },

  // 返回某表面（app/widget）当前要渲染的组件树；可 async
  async view(surface, item, req) {
    if (surface === 'app' && item === 'main') {
      const saved = await kit.storage.getItem('place') || '北京'
      return {
        refreshMs: 0,
        root: {
          type: 'column',
          style: { padding: 16 },
          children: [
            { type: 'text', props: { text: saved + ' · 23°C 晴' },
              style: { fontSize: 22, color: '#D6DCE8' } },
            { type: 'button', props: { label: '刷新' },
              events: { tap: 'refresh' } }
          ]
        }
      }
    }
    return null // 未实现：无变更
  }
}
```

### 4.1 req（请求上下文）

```js
{
  trigger: 'open' | 'refresh' | 'timer' | 'event',
  event: { handler: 'refresh', source: 'tap'|'change', value: <any>, id: '' }, // trigger=event 时
  params: { ... },        // 事件附带参数
  screen: {               // 请求设备的屏幕信息（由容器上报）
    shape: 'circle'|'rect'|'pill',   // 形状（归一化）
    width: 466, height: 466, dpr: 2,
    deviceType: 'watch'|'band'
  }
}
```

### 4.2 组件树协议（Node）

与现有 HydroKit 协议一致，可直接复用：

```json
{
  "type": "column | row | scroll | card | text | icon | image | spinner | badge | button | switch | input",
  "style": { "fontSize": 28, "color": "#FFFFFF", "marginBottom": 8, "...": "CSS 驼峰属性" },
  "children": [ ... ],                  // 仅容器节点
  "events": { "tap": "refresh", "change": "toggle" },  // 用户交互 → 下一次 view() 的 req.event.handler
  "props":  { "text": "hi", "label": "刷新", "checked": true, "value": "北京",
              "placeholder": "输入地名", "name": "battery", "src": "" },
  "media":  { "circle": { "padding": 24 }, "pill": { "fontSize": 22 } }   // 多屏适配（§5.2）
}
```

- 返回值整体为 `{ "root": Node, "refreshMs": 0 }`；返回 `null` 表示"无变更"
- `view` 可以 `async`——Kit 等 Promise 完成
- 多屏适配：请求自带 `req.screen`（§4.1），插件可据此返回不同树；也可用节点级 `media` 让容器按形状自动覆盖样式（优先级：base < 形状键）

## 5. 通信架构

### 5.1 请求链路

```
Vela 手表 / 任意客户端
   │  HTTP POST|GET /api/hydro/{id}/{surface}/{item}/view|refresh|event
   ▼
HydroKit 路由（Go HTTP 服务）        ← 平台中立，与现有实现一致
   │  插件为 JS 插件时：
   │  ① 若该插件 Worker 未启动 → 通知宿主拉起它（按需唤起；本次请求返回"离线"）
   │  ② 把请求打包为消息，放入该插件的待取队列
   │  （Worker 启动时对 Kit 长轮询：GET /api/hydro/worker/poll?package={id}）
   │  ③ Worker 运行 server.js → 返回树 JSON
   │  ④ Worker POST /api/hydro/worker/respond 回传 → Kit 完成 HTTP 响应
   ▼
客户端拿到组件树（Vela 渲染；桌面 Web 表面后续里程碑）
```

要点：

- **Worker 不能监听端口**，因此通道由 Worker 主动发起（**长轮询**）：Worker 持续 `poll` 拉取待处理请求、`respond` 回传结果——仅用标准 `fetch`，不依赖 WebSocket，安卓 WebView 与桌面 WebView 行为一致
- **Kit 内部端点**（`/api/hydro/worker/`，均为 Kit 运行时专用，插件无需感知）：
  - `GET /poll?package=`：请求队列长轮询（25s 窗口）
  - `POST /respond`：回传 view 结果或错误
  - `POST /store`：`kit.storage`（§6，权限 `storage`）
  - `POST /notify`、`/music`、`/lock`、`/commands`：能力 API（§7，按清单权限校验）
  - `GET /asset?package=&path=`：插件包内文件（`kit.loadWasm`/图片，防目录逃逸）
- 同源策略：桌面 WebView 页面通过 asset server 把 `/api/*` 同源转发给本进程 API 服务（worker 使用相对路径），worker 内不再有跨源 fetch 问题（安卓同构：App 内 WebView 同样处理）
- 各插件 **Worker 相互隔离**（一个插件一个 Worker，崩溃不影响其他插件与应用）
- Worker 引导脚本由 Kit 生成（`GetPluginBundle`：CommonJS shim + `kit` 全局 + 轮询泵），前端用 Blob Worker 实例化，前端不参与消息转发

### 5.2 多屏适配

- 每个 `/api/hydro` 请求携带屏幕信息（query 参数：`shape/width/height/dpr/dt`），插件在 `req.screen` 里读取
- 节点 `media` 覆盖块：`{ "style": {...}, "media": { "<shape>": {...} } }`，容器按当前形状合并（shape 键：`circle`/`rect`/`pill`，后写覆盖先写）
- 写法参考：demo 的根节点 `media.circle.padding=24`、`media.pill.padding=12`

## 6. 存储 API（手表端 / 服务端 二选一）

Worker 里没有 `localStorage`，Kit 提供等价接口，且**存储位置可选**：

- `scope: 'server'`（默认）：存**服务端**——宿主应用数据目录（桌面：用户配置目录；安卓：应用数据目录），随宿主持久化
- `scope: 'watch'`：存**手表端**——手表本机存储（Vela `@system.file` 的数据存储目录，参考 [Vela 数据存储文档](https://iot.mi.com/vela/quickapp/zh/features/data/file.html)），数据随手表设备走

```js
await kit.storage.setItem('place', '上海', { scope: 'watch' })   // 手表端
const v = await kit.storage.getItem('place', { scope: 'watch' }) // '上海'；不存在返回 null
await kit.storage.removeItem('place', { scope: 'watch' })
await kit.storage.clear({ scope: 'server' })
```

- 值：任意可 JSON 序列化的值（字符串/数字/布尔/对象），自动序列化
- 语义类比 `localStorage`：同插件键值隔离、异步持久化（可 await）
- 机制：`server` 端直接落盘宿主数据目录；`watch` 端经由 hydro 请求/响应协议同步——容器把手表端该插件的存储快照随请求上送（`req.storage`），响应里的 `storage` 提示字段由容器写入手的表端存储。插件代码层面无感知，`kit.storage` 接口两端一致
- 实现状态：**两端均已可用**。watch 端实现为"请求上下文"语义——容器把插件的手表端存储快照随每个 hydro 请求上送（请求头 `X-Hydro-Store`，插件在 `req.store` 读到）；`kit.storage` 的 watch 写操作累积到当前视图返回值里（`view.storage`），容器收到后合并落盘到设备本机（Vela `@system.file`）。插件代码无感知，接口两端一致

## 7. 能力 API 与权限

插件通过 `kit` 全局对象调用宿主能力；**能力必须在 `manifest.json` 的 `permissions` 里声明**，未声明调用会被拒绝（返回可捕获的异常）。

第一版权限清单：

| 权限 | kit API | 桌面端行为 | 安卓端行为（后续） |
|---|---|---|---|
| `notify` | `kit.notify({title, text})` | 系统通知（beeep） | 系统通知 |
| `music` | `kit.music.action('next'\|'prev'\|'play-pause'...)` | 控制本机媒体 | 控制手机媒体 |
| `lock` | `kit.lock()` | 锁屏 | 锁屏 |
| `commands` | `kit.commands.list()` / `kit.commands.run(id)` | 本机自定义命令 | 后续 |
| `fetch` | 直接使用标准 `fetch()` | 允许网络请求（外网与本机，受 CORS 常规约束） | 同 |
| `storage` | `kit.storage.*`（§6） | 数据目录持久化 | 同 |

**信任边界（重要）**：插件代码以与桌面应用同等的用户身份运行（可访问本机服务、数据目录）。**安装第三方插件 = 信任其代码**。第一版只对单个用户本机自装场景提供，插件市场/签名/沙箱加固是后续里程碑。

## 8. WASM（可选）

- 随包分发：`assets/*.wasm`（打包在 zip 内，发布后不可变）
- `server.js` 通过标准 `WebAssembly.instantiate` 加载（Worker 支持）；Kit 提供等价便捷方法：

```js
const wasm = await kit.loadWasm('assets/model.wasm')  // 返回 { exports }
const { add } = wasm.exports                           // 按需使用
```

- 典型用途：性能关键算法、加密、模型推理（liteRT 模型运行时是新里程碑，与本法无关）
- 纯 JS 先做到功能，需要性能再引入 WASM——**插件不必须带 wasm**

## 9. 插件 WebUI（桌面端设置界面）

插件的 `web/` 目录是它与用户交互的桌面界面——典型用途：**插件设置**、**向 HydroApp 同步信息**（写入存储 → 手表 App 下一次 view/refresh 呈现）。

- **服务**：Kit 以 `GET /api/hydro/webui/{package}/{path}` 同源提供（`index.html` 为默认入口，路径防逃逸、仅限包内 `web/` 目录）；桌面端在插件 Tab 为含 `web/index.html` 的插件提供"界面"按钮，以遮罩层内嵌打开
- **同源**：WebUI 页面与桌面应用同源（asset server 代理），直接 `fetch('/api/hydro/worker/*')` 即可调用 **kit 能力**（按插件 manifest 权限校验，如 `storage`/`notify`）
- **同步到 HydroApp**：WebUI 写入 `kit.storage`（`scope:'server'`）后，手表 App（插件树的 `view()`）在下次渲染时读取即可生效——桌面写、手表读；手表输入走事件回写（§4/§6）
- **示例**：`com.demo.weather`（随源码分发：`desktop/examples/com.demo.weather.hpk`）的 `web/index.html`——城市设置（服务端存储，保存后手表 App 副标题同步）+ 通知演示

## 10. 开发与调试

1. **安装**：把 `xxx.hpk`（zip）解压到插件的安装目录（第一版：宿主持有目录下的 `plugins/{id}/`；界面化安装后续）
2. **日志**：server.js 里 `console.log/error` → 出现在桌面端日志区与终端；Kit 每次 `/api/hydro` 请求也在终端打一行（含屏幕参数）
3. **手动测试**：不依赖手表，直接对宿主发请求即可验证树：

```bash
curl -X POST 'http://127.0.0.1:9000/api/hydro/com.demo.weather/app/main/view?shape=circle&width=466&height=466&dpr=2&dt=watch'
```

4. **参考实现**：随源码分发的示例插件 `desktop/examples/com.demo.weather/`（与旧 Go 内嵌版同构的 JS 版：`view()` 产出同一棵树/事件/多屏 media），可直接安装对照

## 11. 第一版边界（明确不做）

- ~~liteRT / 模型运行时~~（后续里程碑）
- 安卓端实现（后续里程碑；同规范，WebView 宿主，能力 API 换安卓后端）
- 插件市场、签名、可信分发的 UI 流程
- 插件 `web/` 桌面 UI 渲染（空壳页仅创建 Worker；UI 表面后续里程碑）
- 每插件多请求并发排队策略的多 Worker 化（第一版单插件串行处理）

## 版本记录

- v0.1（草案，2026-09-06）：首版规范。与桌面 HydroKit Go 实现同步演进；实现与本文档冲突时以本文档为准（需评审修订）。
- v0.1.1（2026-09-06）：§5 通道实现定为**长轮询**（poll/respond，无 WebSocket 依赖）；§2 生命周期补充"启动即建 + 按需唤起"。
- v0.2（2026-09-06）：**watch 端存储落地**（§6 请求上下文语义：`X-Hydro-Store` 上送快照 / `view.storage` 落盘，容器与 bundle 已实现）；**插件 WebUI 落地**（§9：`web/` 同源服务 + 桌面端遮罩层内嵌）；示例插件更新为 v0.2（名字=watch 端、备注=server 端 + WebUI 设置页）。
- v0.2.1（2026-09-06）：示例组织调整——删除 greeting 示例；**天气示例从 Go 内嵌改为 JS 插件包**（`examples/com.demo.weather.hpk`，含 WebUI），内置插件移除（桌面端无内置插件，全部经安装流程；示例中"城市=服务端/省电模式=手表端"展示双端存储）。
