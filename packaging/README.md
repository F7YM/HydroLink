# 打包（Linux deb/rpm + Windows 安装程序）

版本号**只有一处来源**：`desktop/VERSION`。构建脚本把它同时写进：
- 二进制的自报版本（`-ldflags -X hydrolink-desktop/internal/api.Version=…`，`hydrolink -version` 可查）
- 包元数据的 `Version`

这样"包版本"和"程序自报版本"永远一致，不会出现装的是 1.0.0、程序说是 0.9.0。

## 已定下的元数据（改动前先改这里的文件）

| 项 | 值 | 位置 |
|---|---|---|
| 包名 | `hydrolink`（二进制 `/usr/bin/hydrolink`） | 脚本 / spec |
| 版本 | `desktop/VERSION` | `desktop/VERSION` |
| 许可证 | `Apache-2.0`（LICENSE 文本取自 apache.org 官方文件） | `LICENSE` |
| 维护者 | `float11 <2471825164@qq.com>` | 脚本 / spec |
| 主页 URL | **留空**（仓库未公开；有地址后补 `Homepage`/`URL` 与 AppStream） | — |
| 摘要 | Watch companion server and HydroKit plugin platform | `packaging/deb/control.in`、`packaging/rpm/hydrolink.spec` |
| 长描述 | 中英双语（见下） | 同上 |
| 架构 | amd64 / x86_64（AArch64 需交叉构建环境，见"未做"） | 脚本 |

长描述（两处一致）：

> HydroLink is the desktop side of a watch + desktop setup. It serves the HTTP API the
> watch app talks to (pairing, device status, music, commands, plugins) and hosts the
> HydroKit plugin platform, so installed plugins can render their own interfaces on the
> watch and expose a settings UI on the desktop.
>
> 手表互联服务端：为手表 App 提供配对/状态/音乐/命令/插件接口，并承载 HydroKit 插件
> 平台（插件可在手表上渲染界面、在桌面端提供设置界面）。

## 运行时依赖（按你的选择：全部硬依赖）

| 包 | deb | rpm | 用途 |
|---|---|---|---|
| WebKitGTK 4.1 | `libwebkit2gtk-4.1-0` | `webkit2gtk4.1` | 界面（Wails 的 WebView），缺了完全跑不起来 |
| GTK 3 | `libgtk-3-0 \| libgtk-3-0t64` | `gtk3` | Wails 窗口；deb 侧用「或」兼容 time_t 改名 |
| playerctl | `playerctl` | `playerctl` | 读取/控制音乐（MPRIS） |
| zenity | `zenity` | `zenity` | 非 Windows 平台的致命错误弹框 |

说明：`libgtk-3-0t64` 是 Ubuntu 24.04+/Debian trixie 的 time_t 迁移改名，所以 deb 用二选一。

## 构建

```bash
bash packaging/build-linux-packages.sh          # deb + rpm -> dist/
bash packaging/build-linux-packages.sh deb      # 只出 deb
bash packaging/build-linux-packages.sh rpm      # 只出 rpm
```

产物：

```
dist/hydrolink_<版本>_amd64.deb
dist/hydrolink-<版本>-1.<dist>.x86_64.rpm
```

实现方式（不引第三方打包器）：
- **deb**：`dpkg-deb --build` + `packaging/deb/{control.in,postinst,postrm,copyright.in}`
- **rpm**：`rpmbuild -bb packaging/rpm/hydrolink.spec`。该 spec 打的是**二进制包**：
  文件树由脚本预先编译并按包内路径排好，打成 tar 做 `Source0`，spec 只做解包拷贝
  —— 于是 rpmbuild 不需要 Go 工具链与 `webkit2gtk4.1-devel`。spec 里
  `%global debug_package %{nil}` 关掉自动 debuginfo（无编译步骤时它会因空文件失败）。

## 包内容

```
/usr/bin/hydrolink
/usr/share/applications/hydrolink.desktop
/usr/share/metainfo/com.hydro.hydrolink.metainfo.xml
/usr/share/icons/hicolor/{16,24,32,48,64,128,192,256}x*/apps/hydrolink.png
/usr/share/doc/hydrolink/{LICENSE,README.md,copyright(deb)}
```

- 图标由 `desktop/appicon.png`（192px）用 PIL 生成各尺寸。
- `.desktop` 通过 `desktop-file-validate`（无提示）；AppStream 通过
  `appstreamcli validate`，只剩一条 `url-homepage-missing` 警告（因为 URL 留空）。
- AppStream id 用 `com.hydro.hydrolink`，与插件命名空间（`com.hydro.*`）一致，
  也满足 AppStream 对桌面应用"反向域名 id"的要求。

## 验证命令（每次改完元数据都跑一遍）

```bash
dpkg-deb -I dist/*.deb        # control 字段
dpkg-deb -c dist/*.deb        # 文件清单与权限
rpm -qip dist/*.rpm           # 元数据
rpm -qpR dist/*.rpm           # 依赖
rpm -qlp dist/*.rpm           # 文件清单
rm -rf /tmp/x && mkdir /tmp/x && cd /tmp/x && rpm2cpio <rpm> | cpio -idm   # 解包跑二进制
./usr/bin/hydrolink -version
```

## Windows 安装程序（Inno Setup 6）

- 脚本：`packaging/windows/hydrolink.iss`
- Windows 上构建：`packaging\windows\build-installer.bat`（自动取 `desktop/VERSION`），
  产物 `dist\HydroLink-<版本>-setup.exe`
- 前置：先构建 `desktop\bin\HydroLink-amd64.exe`（`desktop\build-windows.bat`，
  或在 Linux 上 `bash packaging/build-windows.sh` 交叉编译）

设计取舍（都在 iss 里注释了）：

| 决策 | 取值 | 理由 |
|---|---|---|
| 安装范围 | **按用户**（`PrivilegesRequired=lowest`，装到 `%LocalAppData%\Programs\HydroLink`） | 托盘常驻的个人工具，不需要管理员、不弹 UAC；要装 Program Files 用 `/DMyPrivileges=admin` |
| 快捷方式 | 开始菜单必装，桌面可选（默认不勾） | 不弄脏桌面 |
| 开机自启 | 可选任务（`HKCU\...\Run`，卸载自动清） | 手表要随时连得上，但要用户自己决定 |
| WebView2 | 安装时检测注册表；缺了提示并打开**官方下载页** | Win11 自带、Win10 可能没有；不静默下载执行 |
| 卸载 | 先 `taskkill` 结束实例；询问是否删除 `%AppData%\hydrolink`（配对密钥/插件/设置） | 避免文件占用导致卸载失败；默认保留用户数据 |
| 安装器图标 | 用仓库 `desktop/icon.ico` | — |

### 代码签名（强烈建议，否则 SmartScreen 会拦）

1. 买一张 OV/EV 代码签名证书（EV 可即时获得 SmartScreen 信誉）。
2. 在 `hydrolink.iss` 里取消 `SignTool=` 那两行的注释并填证书路径/密码：
   ```ini
   SignTool=signtool sign /fd sha256 /tr http://timestamp.digicert.com /td sha256 /f "C:\path\cert.pfx" /p "密码" $f
   SignedUninstaller=yes
   ```
   （证书是 .pfx 时用 signtool；用硬件令牌/云签名时把命令换成厂商提供的 CLI。）
3. 只签 `HydroLink-amd64.exe` 也可以，但安装包本身不签的话 SmartScreen 仍会提示 ——
   所以最好连安装包一起签（上面的 `SignTool` 就是给安装包用的）。

### 在 Linux 上出安装包（可选）

Inno Setup 本体是 Windows 程序，Linux 上要经 wine：

```bash
# 一次性准备（约 100MB 下载）
sudo dnf install wine
# 下载 Inno Setup 6 安装包并在 wine 前缀里静默安装：
#   wine innosetup-6.x.x.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART
# 然后：
wine "$WINEPREFIX/drive_c/Program Files (x86)/Inno Setup 6/ISCC.exe" \
     /DMyAppVersion=1.0.0 /DMySourceDir=desktop\\bin packaging/windows/hydrolink.iss
```

本仓库**未**在 Linux 上验证这条路径（wine 里的 ISCC 属于额外环境依赖）；更稳的做法是
在 Windows 机器或 CI 的 `windows-latest` runner 上跑 `build-installer.bat`。

## 未做（需要时再加）

- **AArch64**：需要 cgo 交叉编译 + webkit2gtk 的 arm64 sysroot，本机无法验证；
  正确做法是在 arm64 机器/容器里跑同一个脚本（`ARCH_DEB`/`BuildArch` 参数化即可）。
- **SRPM / 源码包**：现在的 spec 是二进制包；要进 Fedora/COPR 得补
  `%prep/%build`（`BuildRequires: golang, webkit2gtk4.1-devel, gtk3-devel`）。
- **仓库托管与签名**：deb 仓库（`reprepro`/`aptly` + GPG）、rpm 仓库
  （`createrepo_c` + GPG 签名）。有 URL 与签名密钥后我可以补上 CI 流程。
- **MSI（WiX）/ MSIX**：企业静默部署或上架商店时再做。
