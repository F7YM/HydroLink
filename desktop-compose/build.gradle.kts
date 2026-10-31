import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // 版本与主仓 android/ 保持一致：Kotlin 2.4.10 + Compose Multiplatform 1.12
    // （miuix-kmp 0.9.4 的 .module 明确要求 kotlin-stdlib 2.4.20+/CMP 1.12.0，
    //  低于这个版本 Gradle 解析出来的依赖会与安卓端不一致）。
    kotlin("multiplatform") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
    // Go 侧返回的 JSON 解析：协议字段全是 snake_case，用序列化插件 + @SerialName 最省事
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
}

group = "com.hydro.link"
version = "1.3.0"

kotlin {
    jvmToolchain(21)

    // 以后 Android 端同一份 UI 代码要搬进 commonMain；现在先只验证桌面能力。
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.0")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
            implementation("org.jetbrains.compose.ui:ui:1.12.0")
            // MD3 引擎（和安卓端同一批 artifact）
            implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
            implementation("org.jetbrains.compose.material:material:1.12.0")
            implementation("org.jetbrains.compose.components:components-resources:1.12.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            // miuix 引擎（Apache-2.0，Compose Multiplatform 库）。
            // 注意 artifact 名字：WindowDialog 在 **miuix-ui** 里（top.yukonga.miuix.kmp.window），
            // 并没有单独的 miuix-window 模块；miuix-preference 是 WindowSpinnerPreference
            //（NavHost 用的是 androidx navigation-compose，和安卓端一致，不引 miuix-navigation）。
            implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
            implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            runtimeOnly(compose.desktop.windows_x64)
            // 插件 WebUI 宿主 + worker 胶水宿主：**系统 WebView**（ca.weblite:webview，MIT）。
            // Linux = 系统 WebKitGTK、macOS = WKWebView、Windows = WebView2，
            // 与 Go 端（Wails 用的是同一套系统 webview）**同一个引擎**；jar 只有 1.4MB
            // （里面是各平台 ~1MB 的原生壳）。
            //
            // 这里原来用的是 compose-webview-multiplatform → KCEF/内嵌 CEF：bundle 539MB、
            // 首启要下载，而且在本机（Fedora + mesa 26）初始化 GLX 时 JOGL 的
            // glXChooseFBConfig 会把 JVM 直接打成 SIGABRT（整个进程 core dump）。
            // 换成系统 WebView 后这 539MB、这次崩溃、以及 JOGL/gluegen 这一整条依赖链都没了。
            implementation("ca.weblite:webview:1.8.0")
            implementation("net.java.dev.jna:jna:5.6.0")
            implementation("net.java.dev.jna:jna-platform:5.6.0")
            // 数据层：JDK 自带的 HttpClient 就够（桌面只有这一个目标，不引 Ktor）
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            // mDNS / Bonjour 局域网服务广播（纯 Java，零 C 依赖）
            implementation("org.jmdns:jmdns:3.5.9")
            // SSH 客户端实现（mwiede/jsch，纯 Java，支持 Ed25519、RSA-SHA2、OpenSSH 密钥格式）
            implementation("com.github.mwiede:jsch:0.2.23")
            // 玻璃/模糊：单独验证（安卓端同样只在这里用，commonMain 不引）
            // 注意：miuix-blur 已从桌面端移除。blur(25dp) 单独用没事，但一旦给内容加
            // layerBackdrop 真正录制背景层，Skia 这条路径会把 JVM 打成 SIGSEGV（实测
            // exit 139，窗口都起不来）。桌面侧栏改用半透明容器色，不做模糊。
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.hydro.link.compose.MainKt"
        jvmArgs += listOf(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
            "-Djava.net.preferIPv4Stack=true",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "HydroLink"
            packageVersion = "1.3.0"
            description = "HydroLink 桌面端"
            modules("jdk.httpserver", "jdk.unsupported", "java.naming", "java.net.http", "java.management")
            linux {
                shortcut = true
                menuGroup = "Utility"
            }
        }
    }
}

afterEvaluate {
    tasks.register<JavaExec>("hydroEventTest") {
        group = "verification"
        description = "HydroKit HTTP 事件到 worker 的字段与存储转发回归"
        mainClass.set("com.hydro.link.server.plugin.HydroEventTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
    }

    // 封面链路（playerctl → artUrl → /api/music/art → 像素 → JPEG → 插件 ctx.music）回归。
    // 桌搭的封面在桌面端曾是一张裂图：剪的是端点压根没实现（404 桩），而插件拿到的
    // artUrl 又是必然 404 的地址——这条测试把它们全钉住。
    tasks.register<JavaExec>("musicArtTest") {
        group = "verification"
        description = "封面端点与 ctx.music 形状回归（含 466x349 三端几何）"
        mainClass.set("com.hydro.link.server.MusicArtTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
    }

    // 存储层一致性测试：同一串操作分别打在 Go 与 Kotlin 的 store 上，diff 落盘文件。
    // 这是「服务端用 Kotlin 重写、行为与 Go 100% 一致」这条要求的可执行判据——
    // 没有它，「一致」只是嘴上说说。
    tasks.register<JavaExec>("storeParity") {
        group = "verification"
        description = "Kotlin store 与 Go store 的落盘结果一致性测试"
        mainClass.set("com.hydro.link.compose.parity.StoreParityMainKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        // 工作目录放在 desktop 那边：要 go run ./cmd/parityinit
        workingDir = file("..")
        args = listOf("/tmp/hydrolink-parity/go", "/tmp/hydrolink-parity/kotlin")
    }

    // HTTP 级一致性测试：同一串请求分别打 Go 进程与 Kotlin 进程，规范化后逐字段 diff
    tasks.register<JavaExec>("httpParity") {
        group = "verification"
        description = "Kotlin HTTP 表面与 Go 的响应级一致性测试"
        mainClass.set("com.hydro.link.compose.parity.HttpParityMainKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    // 验证程序：起本地 HTTP 服务喂同源插件页面，逐项打印 PASS/FAIL，验完自动退出。
    // 这是「系统 WebView 能不能用」的可执行判据——换掉 CEF 之后尤其要靠它，
    // 因为引擎能不能起、JS/localStorage/同源 fetch 通不通，只有真跑一次页面才知道。
    tasks.register<JavaExec>("validation") {
        group = "verification"
        description = "桌面端逐项验证（miuix / MD3 / 系统 WebView）"
        mainClass.set("com.hydro.link.compose.validation.ValidationMainKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
    }

    // 托盘生命周期验证测试
    tasks.register<JavaExec>("trayTest") {
        group = "verification"
        description = "Linux 原生托盘助手生命周期测试"
        mainClass.set("com.hydro.link.compose.tray.NativeTrayTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    // mDNS 广播与自解析验证测试
    tasks.register<JavaExec>("mdnsTest") {
        group = "verification"
        description = "mDNS 广播与自解析验证测试"
        mainClass.set("com.hydro.link.server.mdns.MdnsTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    // 应用检查更新元数据与频控验证测试
    tasks.register<JavaExec>("updateTest") {
        group = "verification"
        description = "应用更新元数据解析与频控逻辑测试"
        mainClass.set("com.hydro.link.compose.update.AppUpdateTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    // 本地插件安装与文件选择器逻辑验证测试
    tasks.register<JavaExec>("localPluginInstallTest") {
        group = "verification"
        description = "本地插件安装包解析与文件选择器逻辑测试"
        mainClass.set("com.hydro.link.compose.plugin.LocalPluginInstallTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    // WebUI HTML <select> 交互增强（Polyfill）验证测试
    tasks.register<JavaExec>("webUiSelectPolyfillTest") {
        group = "verification"
        description = "WebUI HTML <select> 交互增强脚本与注入逻辑测试"
        mainClass.set("com.hydro.link.compose.webui.WebUiSelectPolyfillTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    // HydroKit 原生网络能力（TCP 与 SSH）验证测试
    tasks.register<JavaExec>("netCapabilitiesTest") {
        group = "verification"
        description = "HydroKit 原生网络能力接口（TCP 与 SSH）验证测试"
        mainClass.set("com.hydro.link.server.net.NetCapabilitiesTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    // 单进程排他锁与 IPC 唤醒验证测试
    tasks.register<JavaExec>("singleInstanceLockTest") {
        group = "verification"
        description = "单进程排他锁与 IPC 唤醒验证测试"
        mainClass.set("com.hydro.link.compose.platform.SingleInstanceLockTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    // 配对弹窗触发、去重与状态流转测试
    tasks.register<JavaExec>("pairFlowTest") {
        group = "verification"
        description = "配对弹窗触发、去重与状态流转测试"
        mainClass.set("com.hydro.link.compose.pair.PairFlowTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    // WebUI 诊断
    tasks.register<JavaExec>("webUiDiag") {
        group = "verification"
        description = "WebUI 诊断"
        mainClass.set("com.hydro.link.compose.webui.WebUiDiagKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    tasks.register<JavaExec>("webKitSecurityTest") {
        group = "verification"
        description = "WebKit 安全配置测试"
        mainClass.set("com.hydro.link.compose.webui.WebKitSecurityTestKt")
        classpath = tasks.named<JavaExec>("run").get().classpath
        dependsOn("desktopJar")
        workingDir = file("..")
    }

    tasks.withType<JavaExec>().configureEach {
        jvmArgs(
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED",
        )
        // ca.weblite:webview 的原生壳要拿 AWT 的 native peer（JAWT_GetAWT + X11 reparent），
        // 这两个内部包必须开着（它自己的 README 也这么要求）。
        //
        // 本机 IPv6 路由不通，JVM 默认先试 v6 会直接 socket 超时（curl 同 URL 正常），
        // 所以强制走 IPv4。这不是 workaround，是 JVM 网络栈的已知默认行为差异。
        jvmArgs("-Djava.net.preferIPv4Stack=true")
        if (System.getProperty("os.name").contains("Mac")) {
            jvmArgs(
                "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            )
        }
    }
}

