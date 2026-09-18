# 调试与排错

插件跑在**宿主进程**里（不是手表、也不是浏览器），所以"控制台"在宿主的日志里。
这一章按"从哪看 → 怎么手动打一发 → 常见症状"的顺序来。

## 日志在哪

| 宿主 | 插件 `console.log` 去哪了 |
|---|---|
| 桌面端 | 应用界面「日志」区 + 终端（`./bin/HydroLink` 的 stdout） |
| 安卓端 | 应用内日志区，**同时**进 logcat：`adb logcat -s HydroLink` |

安卓端还能看到 worker 的每一步（插件加载、宿主加载、worker 连上、每条 `/api/hydro` 请求的结果），
所以真机排障第一条命令永远是：

```bash
adb logcat -s HydroLink
```

## 不依赖手表，手动打一发

调试界面最快的方式是直接对宿主发请求，参数就是手表会发的那些：

```bash
# 打开 App 表面（466×466 圆屏、dpr2）
curl -X POST 'http://127.0.0.1:9000/api/hydro/com.demo.weather/app/main/view?shape=circle&width=466&height=466&dpr=2&dt=watch'

# 触发一次事件（点击/开关/输入）
curl -X POST -H 'Content-Type: application/json' \
     -d '{"event":{"handler":"refresh"}}' \
     'http://127.0.0.1:9000/api/hydro/com.demo.weather/app/main/event'
```

返回的就是节点树 JSON——渲染对不对先不用管，树对不对在这一步就能看出来。
注意：**Content-Type 一定要带 `charset=utf-8`**（或干脆别写 `Content-Type`），
否则某些宿主会把请求体按 US-ASCII 解码，中文变乱码。

## 一套现成的自检（安卓端）

```bash
bash android/scripts/device-check.sh      # 手机插着 USB
```

它会把服务状态、插件注册、每个插件出树、乱码、存储往返、封面端点、权限门控、
宿主页与胶水加载逐项打勾，有失败就非零退出。**它跑不通的那一项，就是问题所在。**

## 常见症状 → 先看哪里

| 症状 | 先看 | 常见原因 |
|---|---|---|
| 手表上"加载失败" | `logcat -s HydroLink` 里 `worker connected` 有没有出现 | worker 没起来（宿主 WebView/胶水问题）；或手表与宿主不在同一网络 |
| 插件界面是空的 | 手动 `curl` 那次返回的树 | `view()` 返回了 `null`（宿主回 204，容器可能显示空白） |
| 中文变方块 | 请求头的 charset | 请求体按 US-ASCII 解码了 |
| 改了代码没生效 | 宿主日志里插件版本号 | 插件跑在宿主进程里，重装/重载插件后才会用新代码（宿主不需要重启） |
| `console.log` 看不到 | 宿主日志区 / logcat | 安卓端插件日志前缀是 `hydrokit(worker <id>)` |
| 权限类调用 403 | 调用的能力名与清单 | `permissions` 里没声明该能力（通知/音乐/锁屏/命令/系统） |
| 存储写完读不到 | 是否同一请求内"写完立刻读" | 两端胶水已做写穿；若是手表端存储，还要确认容器在落盘（见 [存储](03-storage.md)） |

平台特有的坑（WebView 渲染进程被杀、服务端口变化、通知权限、NanoHTTPD 字符集等）
集中记在 [安卓端笔记里的坑列表](../android/NOTES.md)。

## 调试用的小技巧

- **先让树正确，再看样式**：把 `view()` 的返回值直接 curl 出来对照协议。
- **屏幕适配**：请求里带 `shape/width/height/dpr/dt`，你可以按形状返回不同树；
  或在节点上用 `media` 让容器按形状覆盖样式（见 [视图](02-view.md)）。
- **刷新**：`refreshMs` 控制容器多久重拉一次；调试时设小一点（如 3000），
  正式发布再放大，否则手表格外耗电。
- **插件是宿主进程里的 WebView**：桌面端与安卓端都一样，所以插件的 CPU/内存开销
  由宿主承担；死循环会拖住宿主（worker 请求有 8s 超时兜底）。
