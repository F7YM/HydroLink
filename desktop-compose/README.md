# desktop-compose

HydroLink 桌面端的**重写工程**（Compose Multiplatform，Kotlin/JVM）。目标是让桌面端与安卓端
共用同一套 UI 代码与同一套视觉：miuix 与 MD3 两个引擎、同样的文案与组件，不再各写一遍。

**当前阶段：技术验证已完成**（见 [VALIDATION.md](VALIDATION.md)）——

| 验证项 | 结果 |
|---|---|
| miuix-kmp 0.9.4 在 desktop JVM 加载并渲染 | ✅ |
| MD3 引擎（material3 1.12.0-alpha03） | ✅ |
| 插件 WebUI 宿主（compose-webview-multiplatform 2.0.3 / KCEF 内嵌 CEF） | ✅ JS 引擎 + localStorage + 同源 fetch 实测通过 |

产品代码还没开始写；`src/desktopMain/.../Main.kt` 是**一次性验证程序**（起本地 HTTP 服务模拟
Go 服务端 + 插件 WebUI，逐项打印 PASS/FAIL，验完自动退出）。

## 版本

与主仓 `android/` 对齐：Gradle 9.4.1 / Kotlin 2.4.10 / Compose Multiplatform 1.12.0 / JDK 21。
miuix-kmp 0.9.4 与 CMP 1.12、Kotlin 2.4 是绑定的（见 VALIDATION.md 的版本依据）。

## 跑验证

```bash
export HYDROLINK_CEF_BUNDLE=~/下载/jbr_jcef-25.0.4.1-linux-x64-b610.67.tar.gz
./gradlew run
```

## 目录

```
src/commonMain/kotlin/com/hydro/link/compose/   # 两端将来共用的代码（现在只有 HostInfo 占位）
src/desktopMain/kotlin/com/hydro/link/compose/  # 桌面壳与验证程序
```
