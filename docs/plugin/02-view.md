# 视图：请求上下文与节点树

插件的 `view()` 收到一次请求上下文（`req`），返回一棵节点树；容器（手表）把它渲染出来。
这一章是 UI 的全部内容：上下文里有什么、能返回哪些节点、样式与屏幕适配、以及事件怎么回来。

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

### req（请求上下文）

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

### 组件树协议（Node）

与现有 HydroKit 协议一致，可直接复用：

```json
{
  "type": "column | row | scroll | card | text | icon | image | spinner | badge | button | switch | input | nav | progress",
  "style": { "fontSize": 28, "color": "#FFFFFF", "marginBottom": 8, "...": "CSS 驼峰属性" },
  "children": [ ... ],                  // 仅容器节点
  "events": { "tap": "refresh", "change": "toggle" },  // 用户交互 → 下一次 view() 的 req.event.handler
  "props":  { "text": "hi", "label": "刷新", "checked": true, "value": "北京",
              "placeholder": "输入地名", "name": "battery", "src": "" },
  "media":  { "circle": { "padding": 24 }, "pill": { "fontSize": 22 } }   // 多屏适配（§5.2）
}
```

- **`progress`（进度条）**：`props.percent`（0-100）、`props.variant`（`horizontal` 默认 / `arc`）；颜色与几何走 `style`：`color`、`layerColor`、`strokeWidth`，弧线另支持 `startAngle`/`totalAngle`/`radius`/`centerX`/`centerY`（对应 Vela `progress` 组件；**角度类数值按度处理**，容器补 `deg`，其余数值补 `px`）
- **绝对定位**：节点 `style.position:"absolute"` + `top/left/right/bottom` 由**容器上提到包装层**执行（叶子在自定义组件内部无定位上下文，包含块尺寸为 0 会被算成 0 尺寸而不渲染）。因此弧线、水印、浮层等可安全使用绝对定位；同时**避免 `left` 与 `right`（或 `top` 与 `bottom`）同时给**，用一组偏移 + 显式宽高最稳
- **层级限制**：容器只展开 3 层（根 → L1 → L2 → L3 叶子），**L3 必须是叶子**（容器放错层级会被渲染成未知叶子占位）；`text/image/box/badge/spinner/button/switch/input/progress/nav` 是叶子
- **全屏背景层**：根节点的 `backgroundImage`/`backgroundSize`/`backgroundPosition`/`backgroundColor` 会被容器提升到**整页容器**（铺满全屏，不受圆屏安全区内边距影响）；其余样式仍作用于内容层。示例：`{"type":"scroll","style":{"backgroundImage":"http://host:9000/api/music/art?size=466&blur=26&dim=0.3","backgroundSize":"cover","padding":16}}`
- 返回值整体为 `{ "root": Node, "refreshMs": 0 }`；返回 `null` 表示"无变更"
- **Widget 的表面形态（卡片树）**：`/api/hydro/{pkg}/widget/{item}/...` 与 app 表面同一套路由/事件，但**渲染约束不同**——widget 树是"卡片树"：
  - 允许节点：`column` / `row` / `text` / `icon` / `image` / `spinner` / `badge` / `button`；**禁用** `nav` / `scroll` / `input` / `switch`
  - **卡片本身不跳转**（点卡片无动作）；`button` 可声明 `props.action:"open"` + `surface/item` → 点击跳转到该插件的 HydroApp（`/pages/hydro`），其余按钮/事件照常 POST 到 `.../event`
  - 手表呈现：**设备信息页的"组件"卡片区**（音乐卡下方独立卡片，每个 widget 一张），按 `refreshMs` 轮询刷新（`/refresh`）；插件即使**只有 widget 没有 app** 也独立可用（卡片就是它的全部表面）
  - HydroApps 启动器只列应用（widget 的唯一入口是设备信息页卡片）
- **滚动由插件声明**：HydroApp 的根节点应为 `type:"scroll"`（容器只给树的纵向滚动容器，`scroll-y` + 回弹；根为其他类型时内容按流动布局，超高部分会被裁）。`scroll` 节点在树内嵌套位置当前按普通 column 处理（后续支持嵌套滚动）
- **页面导航是插件的一部分**：协议节点 `type:"nav"`，`props`: `action:"back"|"open"`、`label`（默认 `‹`/`›`）、`surface`/`item`（仅 open 用，跳转到**同一插件**的另一表面/条目）。容器负责渲染与执行动作（back→返回、open→推入 hydro 页）；HydroApp 应包含导航节点，否则用户无法退出页面（容器只在加载/错误兜底态提供"‹ 返回"）
- `view` 可以 `async`——Kit 等 Promise 完成
- 多屏适配：请求自带 `req.screen`（§4.1），插件可据此返回不同树；也可用节点级 `media` 让容器按形状自动覆盖样式（优先级：base < 形状键）

## 请求是怎么到插件的

你不需要懂信道，但知道这三件事能省很多时间：

1. **手表不直接跑你的代码**。手表把请求发到**宿主**（电脑/手机），宿主里的 HydroKit
   把它派给你的插件，再把返回的树回给手表渲染。所以插件的性能 = 宿主的性能，
   插件的日志在宿主的日志里（见 [调试](07-debugging.md)）。
2. **插件没有服务器**，是宿主主动把请求推给它的（worker 用长轮询领取：`poll` → 执行 → `respond`）。
   所以**第一次请求可能返回"worker 离线"**：宿主这时会顺手把插件拉起来，下一次请求就正常了。
   界面上的表现通常是"第一次打开白一下，刷新即好"。
3. **一次请求 = 一次树**。插件是无状态的（除了 `kit.storage`），每次 `view()` 都从头算一棵树。

### 多屏适配

- 每个 `/api/hydro` 请求携带屏幕信息（query 参数：`shape/width/height/dpr/dt`），插件在 `req.screen` 里读取
- 请求还带 `req.host`：宿主平台（`os` ∈ `linux`/`windows`/`darwin`/`android`、`arch`、`app` 版本，安卓另有 `sdk`）。
  插件按平台分支就靠它，不必嗅探 UA；WASM 插件的 ABI 上下文里另有一份顶层 `ctx.host`
- 节点 `media` 覆盖块：`{ "style": {...}, "media": { "<shape>": {...} } }`，容器按当前形状合并（shape 键：`circle`/`rect`/`pill`，后写覆盖先写）
- 写法参考：demo 的根节点 `media.circle.padding=24`、`media.pill.padding=12`
