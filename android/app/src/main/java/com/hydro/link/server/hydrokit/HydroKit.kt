package com.hydro.link.server.hydrokit

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hydro.link.server.media.AndroidVideoFrames
import com.hydro.link.server.media.MediaJobStore
import com.hydro.link.server.media.MediaRoutes
import com.hydro.link.server.media.VideoFrameSource
import java.io.File

/**
 * 安卓端 HydroKit 的门面：把「插件包仓库 + 注册表 + worker 通道 + 路由」串起来，
 * 并对 HttpServer 暴露一个入口 [handle]。
 *
 * 数据落点：`<filesDir>/hydrokit/`
 *   ├── plugins/<package-id>/…        插件包内容（与桌面同一份 .hpk 解开）
 *   └── plugins-storage/<id>.json     插件服务端存储（卸载保留）
 *
 * 插件执行：JS/WASM 由隐藏 WebView 里的宿主负责（见下一里程碑）；本类只把
 * view 请求通过 [WorkerBridge] 投给 worker，并解析它返回的 View JSON。
 */
class HydroKit(
    context: Context,
    capabilities: KitCapabilities = KitCapabilities.NONE,
    hostAssets: HostAssetProvider = HostAssetProvider.NONE,
    log: (level: String, message: String) -> Unit = { _, _ -> },
) : Runtime.Host {

    private val logFn = log
    private val gson = Gson()

    val store: PluginStore = PluginStore(File(context.filesDir, "hydrokit")).also {
        // 插件清单里的 minHydroLink 拿它比对（与桌面 store.HostVersion 同源）。
        // 这里必须走函数：Kotlin 属性按声明顺序初始化，写成属性时 APP_VERSION 声明在
        // store 之后，构造第 31 行读到 null → setHostVersion 非空参数 NPE（插件 Tab 一点就崩）
        PluginStore.hostVersion = appVersion()
    }
    val bridge: WorkerBridge = WorkerBridge(log = { msg -> logFn("info", msg) })
    val runtime: Runtime = Runtime(this)

    /** 宿主版本（= 产品版本），给 minHydroLink 校验用。 */
    private fun appVersion() = runCatching { com.hydro.link.server.BuildConfig.VERSION_NAME }.getOrDefault("0.0.0")

    /** 宿主平台信息：插件用它按平台分支（os ∈ linux|windows|darwin|android）。 */
    private val hostInfo: MutableMap<String, String> = mutableMapOf(
        "os" to "android",
        "arch" to (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
        "app" to appVersion(),
        "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
    )

    private val kitRoutes = HydroKitRoutes(runtime, store, { level, message -> logFn(level, message) }, { hostInfo }, { capabilities.serverBase() })

    /**
     * 视频切片（media 权限）：宿主解码（[AndroidVideoFrames]，真机才有 MediaCodec）
     * + 作业调度（[MediaJobStore]：前两段预热、其余懒生成、滑窗缓存 ≤2 段）。
     * 分段是**按 URL 下发**的，手表自己按段取，不走 worker 回包（互联通道有 16KB 上限）。
     */
    private val videoFrames: VideoFrameSource =
        AndroidVideoFrames(log = { msg -> logFn("info", msg) })
    private val mediaJobs = MediaJobStore(videoFrames, log = { msg -> logFn("info", msg) })
    private val mediaRoutes = MediaRoutes(mediaJobs, log = { msg -> logFn("info", msg) })

    private val kitApis =
        KitRoutes(store, bridge, capabilities, { msg -> logFn("info", msg) }, { hostInfo }, mediaJobs)
    private val hostPages = HostPageRoutes(hostAssets)

    override fun log(level: String, message: String) = logFn(level, message)

    /** 服务器入口：宿主页 → 切片分段 → kit/worker 端点 → 插件路由；都不匹配返回 null。 */
    fun handle(req: HydroHttpRequest): HydroHttpResponse? =
        hostPages.handle(req) ?: mediaRoutes.handle(req) ?: kitApis.handle(req) ?: kitRoutes.handle(req)

    /**
     * 启动时把磁盘上已安装的插件注册进运行时。
     * 返回注册成功的包 id（坏插件跳过并记日志，不影响其它插件）。
     */
    fun loadInstalledPlugins(): List<String> {
        val registered = mutableListOf<String>()
        for (info in store.list()) {
            if (info.error != null) {
                logFn("warning", "hydrokit: 跳过损坏插件 ${info.id}: ${info.error}")
                continue
            }
            try {
                val manifest = store.manifest(info.id)
                runtime.register(WorkerPlugin(info.id, manifest, bridge))
                registered.add(info.id)
            } catch (e: Exception) {
                logFn("warning", "hydrokit: 注册插件 ${info.id} 失败: ${e.message}")
            }
        }
        return registered
    }

    /** 安装一个 .hpk：装好后热注册（无需重启 App）。 */
    @Throws(HydroException::class)
    fun install(hpk: File): PluginStore.Installed {
        val info = store.install(hpk)
        hotSwap(info.id)
        hosts?.ensure(info.id)
        return info
    }

    /** 卸载插件：注销 + 断开 worker（插件存储保留）。 */
    @Throws(HydroException::class)
    fun uninstall(packageId: String): Boolean {
        runtime.unregister(packageId)
        hosts?.destroy(packageId)
        bridge.disconnect(packageId)
        return store.uninstall(packageId)
    }

    /** 重新加载某插件（安装/更新后调用）：先注销再按新清单注册。 */
    @Throws(HydroException::class)
    fun hotSwap(packageId: String) {
        runtime.unregister(packageId)
        hosts?.destroy(packageId)   // 版本可能变了，宿主必须重建
        bridge.disconnect(packageId)
        val manifest = store.manifest(packageId)
        runtime.register(WorkerPlugin(packageId, manifest, bridge))
    }

    /** worker 宿主管理器（WebView）。attach 之后安装/卸载会自动联动宿主。 */
    var hosts: PluginHostManager? = null

    /** 服务器启动后调用：注册磁盘插件并拉起它们的 worker 宿主。 */
    fun startHosts(manager: PluginHostManager): List<String> {
        hosts = manager
        val ids = loadInstalledPlugins()
        ids.forEach { manager.ensure(it) }
        return ids
    }

    /** 插件清单里是否声明了某权限（kit 能力门控用）。 */
    fun hasPermission(packageId: String, permission: String): Boolean =
        runCatching { store.manifest(packageId).permissions.contains(permission) }.getOrDefault(false)
}

/**
 * 由 worker（WebView 里的 JS/WASM 宿主）提供视图的插件实现，
 * 对应桌面端的 hydrokit.JsPlugin。
 *
 * 投递给 worker 的 payload 是 `{surface,item,req}` 三元组；worker 回填的
 * 结果按 View 解析（`{root,refreshMs,storage}`）；空结果表示「无需更新」。
 */
class WorkerPlugin(
    private val packageId: String,
    private val manifest: Manifest,
    private val bridge: WorkerBridge,
) : Runtime.Plugin {

    private val gson = Gson()

    override fun manifest(): Manifest = manifest

    @Throws(HydroException::class)
    override fun view(surface: String, item: String, req: Request): View? {
        val payload = gson.toJson(
            mapOf(
                "surface" to surface,
                "item" to item,
                "req" to gson.toJsonTree(req),
            )
        )
        val raw = bridge.call(packageId, "view", payload)
        if (raw.isBlank()) return null
        val el = try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            throw HydroException("hydrokit: 插件 $packageId 返回的不是合法 JSON: ${e.message}")
        }
        // 允许两种回包：直接是 View，或 {ok:true,view:{…}}
        val viewJson = if (el.isJsonObject) {
            val obj = el.asJsonObject
            if (obj.has("view") && obj.get("view").isJsonObject) obj.getAsJsonObject("view") else obj
        } else {
            throw HydroException("hydrokit: 插件 $packageId 返回的 JSON 不是对象")
        }
        if (viewJson.entrySet().isEmpty()) return null
        return try {
            gson.fromJson(viewJson, View::class.java)
        } catch (e: Exception) {
            throw HydroException("hydrokit: 解析插件 $packageId 的 View 失败: ${e.message}")
        }
    }
}
