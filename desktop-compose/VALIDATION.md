# 验证结论：桌面端可以用安卓端那一整套

一句话：**可以**。Compose + miuix + MD3 + 插件 WebUI 在桌面 JVM 上全部跑通，三项能力当场验证过，不是纸面推断。

本文件记录**验证过程与踩到的坑**，供后续重写时照着避。

## 验证项与结果

| 验证项 | 结果 | 怎么验的 |
|---|---|---|
| miuix-kmp 0.9.4 在 desktop JVM 加载并渲染 | ✅ | 窗口里渲染 `MiuixTheme` + `Scaffold` + `Card` + `Text`（`top.yukonga.miuix.kmp.basic`），首帧无异常 |
| MD3 引擎同一批 artifact 可用 | ✅ | 同一窗口用 `org.jetbrains.compose.material3:material3:1.12.0-alpha03` 渲染状态文本 |
| 插件 WebUI 宿主（桌面） | ✅ | `compose-webview-multiplatform:2.0.3`（桌面实现 = KCEF 内嵌 CEF）加载 `http://127.0.0.1:<port>/api/hydro/webui/demo/index.html`，页面用 JS 写 `document.title` + `localStorage` + 同源 `fetch`；原生侧执行 JS 读回 `JS-OK\|pong` |
| miuix-blur：离屏背景层 | ✅ | `rememberLayerBackdrop { drawRect(); drawContent() }` 在桌面创建成功（返回非 null），`layerBackdrop`/`drawBackdrop` 正常绘制不崩 |
| miuix-blur：高斯模糊 | ✅ | `RenderEffect.isSupported()=true`；桌面 `BlurEffect` 是 **SkiaBackedRenderEffect**（Skia `ImageFilter`），所以 `blur()`/`textureBlurEffect()` 生效 |
| **Liquid Glass（vibrancy/lens）** | ❌ 桌面**不可用** | `vibrancy()` / `lens()` **不属于 miuix-blur**，来自 `io.github.kyant0:backdrop`（Android 专用 AGSL 库，要 RuntimeShader）。桌面做不出 iOS 那种折射胶囊 |

`JS-OK|pong` 这一条同时证明了插件 WebUI 依赖的三件事：**JS 引擎可用、localStorage 可用、同源 fetch 可用**（插件页面正是靠这些调 kit 端点、存 `splayerLyrics`/`fontScale` 这些设置键、连 SPlayer 的 WebSocket）。

## 版本组合（与主仓 android/ 保持一致）

| 组件 | 版本 | 依据 |
|---|---|---|
| Gradle | 9.4.1 | 与 `android/gradle/wrapper` 相同 |
| Kotlin | 2.4.10 | 与 `android/build.gradle` 相同；miuix 0.9.4 的 `.module` 要求 stdlib ≥ 2.4.20 / CMP 1.12 |
| Compose Multiplatform | 1.12.0 | 同上（miuix 0.9.4 直接要求 1.12.0） |
| miuix-kmp | 0.9.4 | 同上 |
| compose-webview-multiplatform | 2.0.3 | 桌面依赖 `dev.datlag:kcef:2024.04.20.4`；要求 Kotlin ≥ 2.1.20 / CMP ≥ 1.8 |
| JDK | 21（Temurin） | 与 `android/gradle.properties` 的 `org.gradle.java.home` 相同 |

## 踩到的坑（每条都真的卡过）

1. **`google()` 仓库不能少**。Compose Multiplatform 的桌面 artifact 依赖 `androidx.graphics:graphics-shapes`、`androidx.lifecycle:*`、`androidx.savedstate:*`，这些**只在 Google Maven**。只配 `mavenCentral()` 会解析失败。
2. **miuix 没有 `miuix-window` 模块**。`WindowDialog` 在 **`miuix-ui`** 里（包名 `top.yukonga.miuix.kmp.window`）。另外 `miuix-navigation` 没发 0.9.4；NavHost 用 androidx 的 `navigation-compose`（与安卓端一致）。
3. **WebView 的包名是 `com.multiplatform.webview.web`**，不是仓库名 `com.kevinnzou.compose.webview`。按仓库名猜包名会 unresolved。
4. **JVM 必须加 `-Djava.net.preferIPv4Stack=true`**。本机 IPv6 路由不通：JVM 先试 v6 → 连 `api.github.com` 直接 socket 超时（`Socket timeout has expired`），而 curl 同一 URL 0.7s 正常。加这行后 KCEF 初始化立刻正常。
5. **CEF 包不能指望运行时从 GitHub/CDN 下**。`jbr_jcef-25.0.4.1-linux-x64-b610.67.tar.gz` 有 200MB+，本机到 JetBrains CDN 的链路会下到 100% 后 socket 超时，retry 又从 0% 开始。验证时改用官方 `download { custom(url) }` 从**本地**喂（由验证程序自己的 HTTP 服务提供）；解包后 **539MB**（含 `chrome_*.pak`、`icudtl.dat`、`libcef.so` 等）。
6. **桌面侧 KCEF 的 JVM 开关**（官方 README 要求，开发期 `./gradlew run` 也要）：
   `--add-opens java.desktop/sun.awt=ALL-UNNAMED`、`--add-opens java.desktop/java.awt.peer=ALL-UNNAMED`（macOS 再加 `sun.lwawt`、`sun.lwawt.macosx`）。这些要加在 **`afterEvaluate { tasks.withType<JavaExec>() }`**，不能只写进 `compose.desktop.application`（那只对 `run`/`package` 的部分任务生效）。
7. **沙箱里 KCEF 的 `installDir` 解析**：`System.getProperty("compose.application.resources.dir")` 在 dev 下指向 `build/compose/tmp/prepareAppResources/`，装完的 CEF 有 539MB，**必须 gitignore**。

## 桌面底栏怎么办（结论）

安卓端底栏有三档（`LiquidBarMode`）：`LiquidGlass`（vibrancy + lens 折射）/ `Blur`（高斯模糊 + 容器色）/ `None`（纯色）。桌面的情况是：

- `Blur` ✅ 可用（RenderEffect 在桌面是 Skia `ImageFilter`）
- `None` ✅ 可用
- `LiquidGlass` ❌ 不可用（依赖 Android 专用 AGSL 库）

**所以桌面直接用 `Blur` 档**——这与安卓端已有的降级路径是同一套代码（`LiquidGlassNavigationBar` 里 `mode` 的三档判断），不需要为桌面新写分支，只是把默认值定成 `Blur`。这也算一个额外收获：桌面天生就是「Material 风格 + 高斯玻璃」，和 MD3 引擎的观感一致。

## 还没验证的（重写时按风险排序要处理）

- **托盘 / 系统通知 / 开机自启 / 单实例**：Compose Desktop 有 `Tray`、`FileDialog`、单实例自己写锁文件；但现桌面端是 `beeep` + `go-dbus` + `winrt-go` 三套原生实现，替换要三平台实测。
- **打包分发**：Gradle + jpackage（dmg/msi/deb）、代码签名、macOS 公证、CI 重写。
- **与 Go 服务端的接法**：本项目还没接 `desktop/` 的 Go 服务（验证程序自己起了一个本地 HTTP 服务模拟）。正式版应直接打 `api.Server` 的 HTTP 接口（与安卓端同一条路径），不再需要 Wails 绑定层。

## 怎么跑这个验证

```bash
cd desktop-compose

# CEF 包放哪都行；程序按 HYDROLINK_CEF_BUNDLE → ~/下载 → ~/Downloads → . 依次找
export HYDROLINK_CEF_BUNDLE=~/下载/jbr_jcef-25.0.4.1-linux-x64-b610.67.tar.gz

./gradlew run     # 弹出验证窗口，控制台打印每项 PASS/FAIL，跑完自动退出
```
