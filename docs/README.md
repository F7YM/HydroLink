# 文档索引

| 目录 / 文件 | 面向谁 | 内容 |
|---|---|---|
| [plugin/](plugin/README.md) | **插件作者** | HydroPlugin 开发文档：5 分钟上手、清单、视图、存储、能力、WASM、WebUI、调试、打包、协议参考 |
| [platforms/](platforms/README.md) | 宿主维护者 / 排障 | 两端实现与差异：桌面端平台支持、安卓端 HydroKit 移植（含真机验证记录与排查速查） |
| [../README.md](../README.md) | 所有人 | 产品是什么、三端怎么装、目录结构、构建命令 |
| [../packaging/README.md](../packaging/README.md) | 打包者 | deb / rpm / Windows 安装程序的构建与产物说明 |

## 按任务找文档

| 我要做的事 | 看哪篇 |
|---|---|
| 写第一个插件 | [plugin/README.md](plugin/README.md)（5 分钟上手） |
| 查 `manifest.json` 字段 | [plugin/01-manifest.md](plugin/01-manifest.md) |
| 让界面在圆屏/方屏都好看 | [plugin/02-view.md](plugin/02-view.md) |
| 存设置、跨重启保留 | [plugin/03-storage.md](plugin/03-storage.md) |
| 发通知 / 控音乐 / 跑命令 | [plugin/04-capabilities.md](plugin/04-capabilities.md) |
| 用 Rust 写插件 | [plugin/05-runtime.md](plugin/05-runtime.md) |
| 给插件做设置页 | [plugin/06-webui.md](plugin/06-webui.md) |
| 插件在真机上不工作 | [plugin/07-debugging.md](plugin/07-debugging.md) → [platforms/android.md](platforms/android.md)（排查速查） |
| 自己实现一个宿主 | [plugin/99-reference.md](plugin/99-reference.md) |
| 打包桌面端安装包 | [../packaging/README.md](../packaging/README.md) |

> 早期那份单文件规范（`docs/plugin/README.md`）已经拆成上面这套结构化文档；
> 旧链接请按本表跳转。
