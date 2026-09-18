# 能力与权限（kit.*）

通知、音乐、锁屏、命令、系统信息、封面……插件要用这些能力，**必须在清单里声明对应权限**，
否则调用会被宿主拒绝（403）。这一章列清每个能力的接口、权限名，以及两端的前置条件。

插件通过 `kit` 全局对象调用宿主能力；**能力必须在 `manifest.json` 的 `permissions` 里声明**，未声明调用会被拒绝（返回可捕获的异常）。

第一版权限清单：

| 权限 | kit API | 桌面端行为 | 安卓端行为（后续） |
|---|---|---|---|
| `notify` | `kit.notify({title, text})` | 系统通知（beeep） | 系统通知 |
| `music` | `kit.music.action('next'\|'prev'\|'play-pause'...)` + 封面 `artUrl`/`artBlur` | 控制本机媒体；封面走 `GET /api/music/art` | 控制手机媒体；封面同样走 `/api/music/art`（来源是 MediaSession 的 metadata，需通知使用权） |
| `lock` | `kit.lock()` | 锁屏 | 锁屏 |
| `commands` | `kit.commands.list()` / `kit.commands.run(id)` | 本机自定义命令 | 后续 |
| `fetch` | 直接使用标准 `fetch()` | 允许网络请求（外网与本机，受 CORS 常规约束） | 同 |
| `storage` | `kit.storage.*`（§6） | 数据目录持久化 | 同 |

**信任边界（重要）**：插件代码以与桌面应用同等的用户身份运行（可访问本机服务、数据目录）。**安装第三方插件 = 信任其代码**。第一版只对单个用户本机自装场景提供，插件市场/签名/沙箱加固是后续里程碑。
