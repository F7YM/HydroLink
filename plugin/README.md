# 插件源码

这里是 HydroLink 手表端（HydroKit）插件源码，以及打包好的 `.hpk`。

| 目录 | 包名 | 说明 |
|---|---|---|
| `com.demo.weather/` | `com.demo.weather` | 示例天气插件：最小的视图树 + 定时刷新 |
| `com.float11.opencode.ctrl/` | `com.float11.opencode.ctrl` | OpenCode 控制器：会话列表 / Agent、模型切换 / 工具审批 / 发指令 |
| `com.float11.tiktok.control/` | `com.float11.tiktok.control` | 抖音遥控：滑动、点赞、播放暂停（需无障碍或 Shizuku/Root） |
| `com.hydro.bili/` | `com.hydro.bili` | B 站：推荐流、视频详情、播放（可选宿主视频切片） |
| `com.hydro.chat/` | `com.hydro.chat` | 对话式助手界面示例 |
| `deskbuddy/` | `com.hydro.deskbuddy` | 桌搭：模糊封面背景 + 时钟 + 歌名/歌手/歌词 |
| `wasm-fixture/` | `com.hydro.wasmfixture` | WASM 插件夹具（验证 ABI v1 宿主胶水） |

其余 `*.hpk` 是对应目录打包好的成品，客户端直接安装即可。

## 打包

```bash
bash com.hydro.bili/pack.sh        # 跑自测并生成 ../com.hydro.bili.hpk
bash deskbuddy/pack.sh             # 生成 ../com.hydro.deskbuddy.hpk
```

插件协议与能力说明见 GitBook 文档站（清单 / 视图 / 存储 / 能力 / 运行时 / WebUI / 调试 / 打包 / 协议参考）。
