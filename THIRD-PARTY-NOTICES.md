# 第三方组件与致谢

HydroLink 本身以 `GPL-3.0-only` 发布，并包含/改编了下列第三方作品：

## 手表端（`vela/`）

### JSLab

- 项目：<https://github.com/cciccicu/JSLab>
- 作者：`cciccicu`
- 许可证：GNU General Public License v3.0
- 使用范围：`vela/src/pages/hydro/layout/uiLayout.js`（扁平绝对坐标布局编译器）、
  `vela/src/pages/hydro/layout/uiPublisher.js`（原地增量发布器）。
  两者改编自 JSLab 的 `vela-quickapp/src/utils/runtime/{uiLayout,uiPublisher}.js`，
  已针对 HydroKit 协议做较大改写，并在文件头保留来源声明。

## 安卓端（`android/`）

| 组件 | 许可证 | 用途 |
|---|---|---|
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | Liquid Glass 悬浮 Tab 栏及其 catalog 组件移植 |
| [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) | Apache-2.0 | MIUI/HyperOS 风格 Compose 组件与模糊 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | Apache-2.0 | 特权命令执行 |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | BSD-3-Clause | 内置 HTTP 服务端 |

## 桌面端（`desktop-compose/`）

| 组件 | 许可证 | 用途 |
|---|---|---|
| Compose Multiplatform / JetBrains 全家桶 | Apache-2.0 | Compose 桌面端 UI |
| `playerctl` | LGPL-3.0-or-later | 通过 MPRIS 读取/控制系统音乐 |

上述组件均按其各自许可证使用；本仓库对其修改部分同样以 `GPL-3.0-only` 发布。
