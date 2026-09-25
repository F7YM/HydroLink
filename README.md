# HydroLink

手表（Vela）+ 手机（安卓）+ 桌面（Linux/Windows/macOS）互联系统：各端提供 HTTP
服务端，手表 App 通过它与手机/桌面通讯；同时承载 **HydroKit** 插件平台。

- **HydroLink** = 产品（手表 + 手机 + 桌面互联 + 插件平台）
- **HydroKit** = 插件平台（运行时 + SDK + HTTP 路由；桌面与安卓各有一份同协议实现）
- **HydroPlugin** = 可分发的插件包（`.hpk`，本质是 zip）

## 目录

| 路径 | 内容 |
|---|---|
| `desktop/` | 桌面端（Go + Wails v2）：HTTP 服务端 + 图形界面 + 插件运行时 |
| `vela/` | 手表端（Vela 快应用）：HydroApps 启动器 + 插件容器 + 桌搭组件 |
| `android/` | 安卓端（Kotlin）：HTTP 服务端 + 互联通道（XMS Wearable）+ 完整 HydroKit |
| `packaging/` | 打包：Linux 的 deb/rpm/Arch、Windows 的安装程序（Inno Setup） |

插件开发文档在 GitBook 文档站：清单 / 视图 / 存储 / 能力 / 运行时 / WebUI / 调试 / 打包 / 协议参考。

## 构建

```bash
# Linux（需要 webkit2gtk-4.1 开发包）
cd desktop && bash build-linux.sh          # 产物 bin/HydroLink

# 无头模式（只跑 HTTP 服务端，不起窗口）
./bin/HydroLink -no-gui -port 9000

# 版本
./bin/HydroLink -version
```

Windows / macOS 见 `desktop/PLATFORMS.md`（含各自构建命令与已知限制）。

## 打包

```bash
bash packaging/build-linux-packages.sh          # dist/ 下的 deb / rpm / Arch 包
```

Windows 安装程序（Inno Setup）见 `packaging/windows/README.md`。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。

## 安卓端

安卓端是手机侧的 HydroLink：HTTP 服务端（默认 9000）+ 互联通道（XMS Wearable，
手表不方便走 WiFi 时用）+ **完整 HydroKit**（与桌面同一套 `.hpk` 与协议）。

```bash
cd android
./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 132 个 JVM 单测（含真产物 e2e）
```

真机自检（手机插着 USB）：

```bash
bash android/scripts/device-check.sh
```
