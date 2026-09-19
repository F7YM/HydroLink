# 能力与权限（kit.*）

通知、音乐、锁屏、命令、系统信息、封面……插件要用这些能力，**必须在清单里声明对应权限**，
否则调用会被宿主拒绝（403）。这一章列清每个能力的接口、权限名，以及两端的前置条件。

插件通过 `kit` 全局对象调用宿主能力；**能力必须在 `manifest.json` 的 `permissions` 里声明**，未声明调用会被拒绝（返回可捕获的异常）。

第一版权限清单：

| 权限 | kit API | 桌面端行为 | 安卓端行为（后续） |
|---|---|---|---|
| `notify` | `kit.notify({title, text})` | 系统通知（beeep） | 系统通知 |
| `music` | `kit.music.action('next'\|'prev'\|'play-pause'...)` + 封面 `artUrl`/`artBlur` + 歌词 `lyrics` | 控制本机媒体；封面走 `GET /api/music/art`；可接 **SPlayer 实时歌词**（见下） | 控制手机媒体；封面同样走 `/api/music/art`（来源是 MediaSession 的 metadata，需通知使用权）；**没有歌词来源** |
| `lock` | `kit.lock()` | 锁屏 | 锁屏 |
| `commands` | `kit.commands.list()` / `kit.commands.run(id)` | 本机自定义命令 | 后续 |
| `fetch` | 直接使用标准 `fetch()` | 允许网络请求（外网与本机，受 CORS 常规约束） | 同 |
| `storage` | `kit.storage.*`（§6） | 数据目录持久化 | 同 |

### SPlayer 实时歌词（桌面端专属）

桌面上跑着 [SPlayer](https://splayer.imsyy.top/) 时，宿主可以连它的本地 WebSocket 服务
（默认 `ws://127.0.0.1:25885`，可在 SPlayer 设置里改）拿实时歌词，插件在
`ctx.music.lyrics`（WASM）/ `music.lyrics`（JS）里读：

```json
{ "source": "splayer", "line": "当前这一句", "next": "下一句", "positionMs": 12500 }
```

**怎么开**：这是**插件自己的开关**——插件在自己的 WebUI 里让用户勾选，写两个自己
server 作用域的存储键，宿主只读它们：

| 键 | 类型 | 说明 |
|---|---|---|
| `splayerLyrics` | bool | 是否开启 |
| `splayerPort` | number | SPlayer 的 WebSocket 端口，默认 25885 |

宿主**按需连接**：只有插件视图在轮询、或插件 WebUI 打开着（`POST /api/hydro/worker/splayer`
取状态）时才会连；20 秒没人取就断开，不常驻后台。

插件 WebUI 用 **`GET /api/hydro/worker/platform`** 判断宿主 `os`（`windows`/`linux`/`darwin`/`android`），
只在桌面端显示这个开关——安卓端没有本地 SPlayer，宿主不会返回 `lyrics` 字段。
`desktop/examples/deskbuddy/web/index.html` 是现成的实现范例。

**信任边界（重要）**：插件代码以与桌面应用同等的用户身份运行（可访问本机服务、数据目录）。**安装第三方插件 = 信任其代码**。第一版只对单个用户本机自装场景提供，插件市场/签名/沙箱加固是后续里程碑。
