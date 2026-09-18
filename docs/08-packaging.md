# 打包、示例与边界

## 打包：`.hpk` 就是一个 zip

按 [包结构](01-manifest.md) 备好目录后打包即可：

```bash
cd hello
zip -r ../com.demo.hello.hpk . -x '.*'          # 把文件名改回 .hpk 的 zip
```

约定（宿主会校验）：

- 包内根目录（或**单一顶层目录**内）必须有 `manifest.json`；
- 必须有清单里 `entry` 指定的入口文件（JS 默认 `server.js`，`runtime:"wasm"` 默认 `plugin.wasm`）；
- 扩展名 `.hpk` 或 `.zip`；安装包里的路径不能越界（`../` 会被拒）；
- **版本只能升不能降**：装一个更低的版本会被拒（防误装旧包）。

## 安装

| 宿主 | 步骤 |
|---|---|
| 桌面端 | 界面「插件」→ 安装插件 → 选 `.hpk` → 确认弹窗（列出它将获得的权限）→ 安装 |
| 安卓端 | 底部「插件」Tab → 安装 .hpk → 选文件 → 同样的确认弹窗 |

安装是**热生效**的：装完插件立刻注册，worker 宿主随即拉起，不需要重启宿主。
卸载会保留插件的服务端存储（重装后设置还在）。

## 两个完整示例

| 示例 | 路径 | 说明 |
|---|---|---|
| 天气（JS） | `desktop/examples/com.demo.weather/` | `server.js` + `web/` 设置页；演示 `view()` 分发、事件、`media` 多屏适配、两种存储作用域。仓库里有打包好的 `com.demo.weather.hpk` |
| 桌搭（WASM） | `desktop/examples/deskbuddy/` | Rust 写的 WASM 插件：时钟 + 歌名 + 专辑封面背景；`pack.sh` 一键 `cargo build --target wasm32-unknown-unknown` + 打包 |

WASM 示例的打包脚本（可以直接照抄）：

```bash
bash desktop/examples/deskbuddy/pack.sh          # 编译 + 打包
bash desktop/examples/deskbuddy/pack.sh --test   # 先跑 cargo test
```

## 第一版明确不做

- 插件市场、签名、可信分发的 UI 流程（现在只能自己装 `.hpk`）
- 插件 `web/` 页面在**桌面端**的深度集成（目前是宿主窗口里的 WebView 遮罩层）
- 单插件多 worker 并发（第一版是单插件串行处理，两端一致）
- liteRT / 模型运行时等重型能力

> 安全提醒：插件代码以宿主同等权限运行（能访问本机服务、数据目录、已声明的能力），
> 安装任何来源不明的插件都等于把机器交给它。两端在安装前都会弹确认框列出它申请的权限——
> 看清再点。
