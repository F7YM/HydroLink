# HydroLink

手表（Vela）+ 手机（安卓）+ 桌面（Linux/Windows/macOS）互联系统：各端提供 HTTP
服务端，手表 App 通过它与手机/桌面通讯；同时承载 **HydroKit** 插件平台。

- **HydroLink** = 产品（手表 + 手机 + 桌面互联 + 插件平台）
- **HydroKit** = 插件平台（运行时 + SDK + HTTP 路由；桌面与安卓各有一份同协议实现）
- **HydroPlugin** = 可分发的插件包（`.hpk`，本质是 zip）

## 目录

| 路径 | 内容 |
|---|---|
| `vela/` | 手表端（Vela 快应用）：HydroApps 启动器 + 插件容器 + 桌搭组件 + 扁平渲染引擎 |
| `android/` | 安卓端（Kotlin）：HTTP 服务端 + 互联通道（XMS Wearable）+ 完整 HydroKit |
| `desktop-compose/` | 桌面端（Compose Multiplatform，Kotlin/JVM）：HTTP 服务端 + 完整 HydroKit + 系统 WebView 宿主 |
| `plugin/` | 插件源码与已打包的 `.hpk`（天气、B 站、桌搭、OpenCode 控制器等） |
| `packaging/` | 打包：Linux 的 deb/rpm/Arch、Windows 的安装程序（Inno Setup） |

插件开发文档在 GitBook 文档站：清单 / 视图 / 存储 / 能力 / 运行时 / WebUI / 调试 / 打包 / 协议参考。

## 构建

### 手表端（Vela 快应用）

```bash
cd vela
npm install
npm run build      # Debug RPK -> dist/com.hydro.link.debug.<版本>.rpk
npm run release    # Release RPK（JSC 字节码，体积更小）
```

排版引擎与沙盒的纯 JS 单测（不需要手表）：

```bash
node vela/src/pages/hydro/layout/test-layout.mjs
node vela/src/pages/hydro/layout/test-client-runtime.mjs
```

### 安卓端

```bash
cd android
./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM 单测（含真产物 e2e）
```

真机自检（手机插着 USB）：

```bash
bash android/scripts/device-check.sh
```

### 桌面端（Compose Multiplatform）

```bash
cd desktop-compose
./gradlew run                       # 启动桌面端
./gradlew musicArtTest hydroEventTest
```

### 插件包

```bash
bash plugin/com.hydro.bili/pack.sh              # 重新打包并跑插件自测
```

## 打包

```bash
bash packaging/build-compose-linux.sh     # dist/ 下的 deb / rpm / Arch 包
```

Windows 安装程序（Inno Setup）见 `packaging/windows/`。

## 许可证

HydroLink 以 **GNU General Public License v3.0**（`GPL-3.0-only`）发布，见 [LICENSE](LICENSE)。

手表端 `vela/` 的扁平布局编译器与增量发布器改编自
[cciccicu/JSLab](https://github.com/cciccicu/JSLab)（GPL-3.0），详见
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## 参与开发

- 签名私钥（安卓 `*.keystore`、手表 `vela/sign/**/private.pem`）**不入库**：
  本地保留 `android/app/hydrolink-release.keystore` 与 `vela/sign/{,release/,debug/}private.pem`
  即可；发布包由维护者用同一套密钥签名（升级必须沿用，换了密钥旧版本装不上，
  配对过的设备也会失联）。仓库里只保留公开的 `certificate.pem`。
- 换机器 / 新克隆仓库后，把手表密钥放回 `vela/sign/`（或让 `aiot` 工具重新生成一套，
  此时证书指纹会变，需在设备上重装并重新配对）。
- 安卓签名配置从 `android/local.properties`（或同名环境变量）读取
  `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` /
  `RELEASE_KEY_PASSWORD`；缺省时 release 回退到 debug 签名，仅用于本地验证。
