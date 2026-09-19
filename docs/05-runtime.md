# 运行时：JS 与 WASM（ABI v1）

插件可以用 JS 写（改一行就能跑），也可以编译成 WASM（Rust 等语言、性能敏感场景）。
两种运行时共用同一套协议与宿主胶水；WASM 走 ABI v1（JSON over linear memory），本章给出
完整约定与工具链。

插件清单用 `runtime` 声明运行方式（默认 `js`）：

| runtime | 入口 | 执行方式 |
|---|---|---|
| `js`（默认） | `server.js` | Worker 内直接执行（CommonJS shim + `kit` 全局） |
| `wasm` | `plugin.wasm` | Worker 内的 **HydroKit WASM 宿主**加载并调用（本节 ABI） |

### WASM 插件是"纯函数"

WASM 无法 `await` 异步宿主调用，因此 ABI v1 把插件定义为**纯函数**：

- **读**：宿主预取上下文（请求、时间、系统指标、音乐、本插件服务端存储快照），一次性交给插件
- **写**：插件不直接调宿主，而是在返回值里给出 **effects 列表**，宿主代为执行（仍按清单权限校验）

### ABI v1（JSON over linear memory）

导出（guest）：

```
hydro_abi_version() -> i32            # 必须为 1
hydro_alloc(len) -> ptr               # guest 分配器
hydro_free(ptr, len)
hydro_view(ctxPtr, ctxLen) -> ptr     # 返回信封：[u32 小端长度][utf-8 JSON]
```

导入（host → 宿主提供）：

```
env.hydro_log(ptr, len)               # 可选，调试输出（进桌面端日志）
```

**上下文 JSON**（`hydro_view` 入参，字段按清单权限填充）：

```json
{
  "abi": 1, "package": "com.hydro.deskbuddy",
  "surface": "app", "item": "main",
  "req": { "trigger": "open", "event": {...}, "screen": {...}, "store": {...} },
  "time":   { "unix": 0, "iso": "...", "hour": 9, "minute": 41 },
  "host":   { "os": "linux", "arch": "amd64", "app": "1.0.0" },  // 安卓: os="android"，另有 sdk
  "server": { "base": "http://192.168.3.48:9000" },
  "system": { "cpu": 12.5, "memPercent": 43.2, "memUsedMB": 8192, "memTotalMB": 16384 },
  "music":  { "hasMedia": true, "title": "Feel Something", "artist": "Jaymes Young", "playing": true,
              "artUrl": "...", "artBlur": "...",
              "lyrics": { "source": "splayer", "line": "当前这一句", "next": "下一句", "positionMs": 12500 } },
  "storage": { "key": "value" }
}
```
（`system` 需权限 `system`；`music` 需 `music`；`storage` 需 `storage`；`req.screen` / `req.store` 始终提供。）

`music` 里的 `artUrl`/`artBlur` 是封面地址，`lyrics` 是**宿主拿到的实时歌词**：目前只有桌面端
能提供（插件开了 SPlayer 歌词集成后，宿主连 SPlayer 的 WebSocket 并把当前这一句换算好，
见 §能力一章）。拿不到时整个 `lyrics` 字段缺失，插件按"没有歌词"渲染即可（安卓端永远没有）。

**返回值 JSON**：

```json
{
  "root": { "type": "scroll", "...": "..." },
  "refreshMs": 3000,
  "storage": { "watchKey": "值" },
  "effects": [
    { "op": "notify",  "title": "标题", "text": "内容" },
    { "op": "music",   "action": "play-pause" },
    { "op": "storage", "key": "k", "value": "v" },
    { "op": "lock" }
  ]
}
```

`root`/`refreshMs`/`storage` 语义与 JS 插件一致（§4/§6）；`effects` 由宿主执行，未知 `op` 忽略并记日志。

### 打包与工具链

- zip 内放 `plugin.wasm`（+ 可选 `assets/`），清单写 `"runtime": "wasm"`、`"entry": "plugin.wasm"`
- SDK/示例：
  - `desktop/examples/wasm-fixture/`（Rust，零依赖，最小 ABI 实现）
  - `desktop/examples/deskbuddy/`（完整插件：纯函数 view + 调参 WebUI + 单测）
  - 打包脚本：`bash desktop/examples/deskbuddy/pack.sh [--test]`（编译 wasm → 收集 `web/` → 产出 `.hpk`）
  - **纯函数便于测试**：`cargo test` 直接喂 JSON 上下文断言产出树/effects，无需任何宿主（宿主侧另有 node + Go 的端到端测试兜底）
- 语言任选，只要产出符合 ABI 的 wasm：Rust（推荐，体积最小）、AssemblyScript、TinyGo；Go 1.24+ 也可用 `GOOS=wasip1` + `//go:wasmexport`（需宿主提供 WASI 导入桩）
- `server.js` 里同样可以用 `kit.loadWasm('assets/x.wasm')` 加载**辅助** wasm（性能关键算法、模型）——那是 JS 插件的可选手段，与本节"WASM 插件"不同
