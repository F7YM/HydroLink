# HydroLink

手表（Vela）+ 桌面（Linux/Windows/macOS）互联系统：桌面端提供 HTTP 服务端，
手表 App 通过它与桌面通讯；同时承载 **HydroKit** 插件平台。

- **HydroLink** = 产品（手表 + 桌面互联）
- **HydroKit** = 插件平台（运行时 + SDK + HTTP 路由）
- **HydroPlugin** = 可分发的插件包（`.hpk`，本质是 zip）

## 目录

| 路径 | 内容 |
|---|---|
| `desktop/` | 桌面端（Go + Wails v2）：HTTP 服务端 + 图形界面 + 插件运行时 |
| `vela/` | 手表端（Vela 快应用） |
| `android/` | 安卓端（后续里程碑） |
| `docs/` | 文档：插件规范 `hydroplugin.md`、平台支持 `desktop-platforms.md` |
| `packaging/` | 打包：Linux 的 deb/rpm、Windows 的安装程序（Inno Setup） |

## 构建

```bash
# Linux（需要 webkit2gtk-4.1 开发包）
cd desktop && bash build-linux.sh          # 产物 bin/HydroLink

# 无头模式（只跑 HTTP 服务端，不起窗口）
./bin/HydroLink -no-gui -port 9000

# 版本
./bin/HydroLink -version
```

Windows / macOS 见 `docs/desktop-platforms.md`（含各自构建命令与已知限制）。

## 打包

```bash
bash packaging/build-linux-packages.sh          # dist/*.deb 与 dist/*.rpm
```

Windows 安装程序（Inno Setup）见 `packaging/windows/README.md`。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。
