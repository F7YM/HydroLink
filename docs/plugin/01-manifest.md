# 插件清单与包结构

一个 HydroPlugin 就是一个 zip（后缀 `.hpk`），里面最重要的两个东西是 `manifest.json`（清单）
和入口文件（`server.js` 或 `plugin.wasm`）。清单决定了插件在手表上叫什么、有什么界面、
要哪些权限。

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

### manifest.json 字段

> **`apps[].icon` / `widgets[].icon`**：条目图标。可以写**包内相对路径**（推荐，
> 例如 `assets/icon.png`）或绝对 URL（`https://…`）。写相对路径时，宿主在
> `/api/hydro/list` 里会把它解析成手表能直接取的可取 URL（走免鉴权的 asset 路由），
> 启动器直接用它渲染；不写则启动器显示插件名首字兜底。图标建议 96×96 以上、方形。

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

> **item id 形式（约定，当前不强制校验）**：`id` 是包内短标识，会进入 URL 路径（`/api/hydro/{package}/{surface}/{item}/...`），因此**必须是 URL 安全的**。约定形式：小写字母 / 数字 / `-` / `_` 的短 slug，如 `main`、`now`、`settings`、`hourly_forecast`（≤64 字符）；用户可读的名字放 `name`。同一 surface 内 id 必须唯一（apps 与 widgets 之间可以重复，因为 surface 不同）
| `permissions` | string[] | 能力权限声明（§7 权限清单，未声明即不可调用） |
