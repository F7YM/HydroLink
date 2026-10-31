# 打包（Linux deb/rpm/Arch + Windows 安装程序）

桌面端使用 Compose Multiplatform 构建；打包脚本把 `desktop-compose` 产出的可分发
运行时（`createDistributable` / `packageMsi` 的产物）塞进各平台包格式里。

## 版本号

当前由脚本内的 `VERSION` 变量给出（`packaging/build-compose-linux.sh`），
与 `desktop-compose/build.gradle.kts` 的版本保持一致。发布前记得一起改。

## 已定下的元数据（改动前先改这里的文件）

| 项 | 值 | 位置 |
|---|---|---|
| 包名 | `hydrolink`（`/usr/bin/hydrolink`） | 脚本 / spec |
| 版本 | `1.3.0` | `packaging/build-compose-linux.sh` |
| 许可证 | `GPL-3.0-only`（LICENSE 文本取自 gnu.org 官方文件） | `LICENSE` |
| 维护者 | `float11 <2471825164@qq.com>` | 脚本 / spec |
| 主页 URL | **留空**（待补 `Homepage`/`URL` 与 AppStream） | — |
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
| WebKitGTK 4.1 | `libwebkit2gtk-4.1-0` | `webkit2gtk4.1` | 插件 WebUI / 内嵌页面 |
| GTK 3 | `libgtk-3-0 \| libgtk-3-0t64` | `gtk3` | Compose 窗口 |
| playerctl | `playerctl` | `playerctl` | 读取/控制音乐（MPRIS） |
| zenity | `zenity` | `zenity` | 非 Windows 平台的致命错误弹框 |

说明：`libgtk-3-0t64` 是 Ubuntu 24.04+/Debian trixie 的 time_t 迁移改名，所以 deb 用二选一。

## 构建

```bash
# 先出 Compose 可分发运行时
cd desktop-compose && ./gradlew createDistributable

# 再打包（deb + rpm + Arch）
bash packaging/build-compose-linux.sh

# Windows（在 Linux 上准备目录 + 内嵌 JRE，安装包在 Windows 上用 Inno Setup 出）
bash packaging/build-compose-windows.sh
```

产物：

```
dist/hydrolink_<版本>_amd64.deb
dist/hydrolink-<版本>-1.<dist>.x86_64.rpm
dist/hydrolink-<版本>-1-x86_64.pkg.tar.zst
```

实现方式（不引第三方打包器）：
- **deb**：`dpkg-deb --build` + `packaging/deb/{control.in,postinst,postrm,copyright.in}`
- **rpm**：`rpmbuild -bb packaging/rpm/hydrolink.spec`。该 spec 打的是**二进制包**：
  文件树由脚本预先排好，打成 tar 做 `Source0`，spec 只做解包拷贝。
  `%global debug_package %{nil}` 关掉自动 debuginfo（无编译步骤时它会因空文件失败）。
- **Arch**：`packaging/build-compose-arch.sh` 手搓 `.pkg.tar.zst`，不需要 makepkg。

## 包内容

```
/opt/hydrolink/                     Compose 运行时（bin/、lib/app/、lib/runtime/）
/usr/bin/hydrolink
/usr/share/applications/hydrolink.desktop
/usr/share/metainfo/com.hydro.hydrolink.metainfo.xml
/usr/share/icons/hicolor/{16,24,32,48,64,128,192,256}x*/apps/hydrolink.png
/usr/share/doc/hydrolink/{LICENSE,README.md,copyright(deb)}
```

- 图标由 `packaging/build/icons/hydrolink-*.png` 提供（打包前生成）。
- `.desktop` 通过 `desktop-file-validate`；AppStream 通过 `appstreamcli validate`，
  目前只剩一条 `url-homepage-missing` 警告（因为主页 URL 留空）。
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
./opt/hydrolink/bin/HydroLink -version
```

## Windows 安装程序（Inno Setup 6）

- 脚本：`packaging/windows/hydrolink-compose.iss`
- 准备：`bash packaging/build-compose-windows.sh`（编译启动引导器、内嵌 JRE、
  解出 skiko 原生库）
- 在 Windows 上跑 ISCC 生成 `dist\HydroLink-<版本>-setup.exe`

踩过的坑（都已修，写在这里免得重犯）：

- **`.bat` 必须保持纯 ASCII**：cmd.exe 按系统 ANSI 代码页读 `.bat`，UTF-8 中文注释
  在 GBK 环境下会被解码成乱码，整份脚本的行都被拆坏（表现为
  `'鐗堟湰' 不是内部或外部命令` 之类）。中文说明放本 README，脚本里只用英文。
- **`.iss` 要存成 UTF-8 with BOM**：脚本里有中文（任务描述、卸载询问），
  没有 BOM 时 Inno 可能按本地代码页解析导致乱码。
- **路径从脚本位置推导**：用 Inno 预定义的 `SourcePath` 拼接，与当前工作目录无关；
  产物缺失时用 `#error` 给出该跑哪条命令（而不是让 ISCC 报 "Source file ... does not exist"）。
- **skiko 原生库必须在 `lib/app` 里**：启动器给 java 传
  `-Dskiko.library.path=<安装目录>/lib/app`，skiko 只从那里找 dll，找不到就是
  「HydroLink 启动异常：Can't load library: …skiko-windows-x64.dll」。打包脚本会从
  `skiko-awt-runtime-windows-x64-*.jar` 里解出来。
