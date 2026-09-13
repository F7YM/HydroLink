package com.hydro.link.server.hydrokit

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/** HydroKit 调用出错（对应 Go 侧的 error）：HTTP 层映射成 404/400。 */
class HydroException(message: String) : Exception(message)

/**
 * 清单解析与校验（对应 desktop/internal/hydrokit/manifest.go）。
 *
 * 校验规则与桌面端保持一致：
 *   - package.id 必填
 *   - 至少要注册一个 app 或 widget
 *   - 同一 surface 内 item id 唯一且非空
 */
object Manifests {
    private val gson = Gson()

    @Throws(HydroException::class)
    fun parse(json: String): Manifest {
        val m = try {
            gson.fromJson(json, Manifest::class.java) ?: Manifest()
        } catch (e: JsonSyntaxException) {
            throw HydroException("hydrokit: manifest 不是合法 JSON: ${e.message}")
        }
        validate(m)
        return m
    }

    @Throws(HydroException::class)
    fun validate(m: Manifest) {
        if (m.pkg.id.isBlank()) throw HydroException("hydrokit: manifest 缺少 package.id")
        if (m.apps.isEmpty() && m.widgets.isEmpty()) {
            throw HydroException("hydrokit: manifest 没有注册任何 app 或 widget")
        }
        uniqueItems("app", m.apps)
        uniqueItems("widget", m.widgets)
    }

    @Throws(HydroException::class)
    private fun uniqueItems(kind: String, items: List<Item>) {
        val seen = mutableSetOf<String>()
        for (it in items) {
            if (it.id.isBlank()) throw HydroException("hydrokit: $kind item 缺少 id")
            if (!seen.add(it.id)) throw HydroException("hydrokit: $kind id 重复: ${it.id}")
        }
    }

    fun hasApp(m: Manifest, id: String): Boolean = m.apps.any { it.id == id }
    fun hasWidget(m: Manifest, id: String): Boolean = m.widgets.any { it.id == id }
}

/**
 * 注册表（对应 desktop/internal/hydrokit/runtime.go）。
 *
 * 线程安全：桌面端用 RWMutex，这里用 synchronized + 快照，避免读时持锁调用插件。
 */
class Runtime(private val host: Host = Host.NOP) {

    /** 宿主能力（目前只有日志；后续里程碑加 http/kv/system 等权限门控能力）。 */
    interface Host {
        fun log(level: String, message: String)

        companion object {
            val NOP = object : Host {
                override fun log(level: String, message: String) {}
            }
        }
    }

    /** 插件实现（JS/WASM 插件由 WorkerBridge 支撑）。 */
    interface Plugin {
        fun manifest(): Manifest
        /** 返回 null 表示「无需更新」（对应 Go 的 (nil, nil)）。 */
        @Throws(HydroException::class)
        fun view(surface: String, item: String, req: Request): View?
    }

    private val lock = Any()
    private val plugins = LinkedHashMap<String, Plugin>()

    @Throws(HydroException::class)
    fun register(plugin: Plugin) {
        val m = plugin.manifest()
        Manifests.validate(m)
        val id = m.pkg.id
        synchronized(lock) {
            if (plugins.containsKey(id)) throw HydroException("hydrokit: 插件 $id 已注册")
            plugins[id] = plugin
        }
        host.log(
            "info",
            "hydrokit: 已注册插件 $id v${m.pkg.version} " +
                "(${m.apps.size} apps, ${m.widgets.size} widgets)",
        )
    }

    /** 注销插件（卸载插件包时调用；其余插件继续服务）。 */
    fun unregister(packageId: String) {
        synchronized(lock) { plugins.remove(packageId) }
    }

    fun lookup(packageId: String): Plugin? = synchronized(lock) { plugins[packageId] }

    fun pluginIds(): List<String> = synchronized(lock) { plugins.keys.toList() }

    /** 启动器用：列出所有已注册插件的摘要。 */
    fun list(): List<PluginSummary> = synchronized(lock) {
        plugins.values.map { p ->
            val m = p.manifest()
            PluginSummary(m.pkg, m.apps, m.widgets)
        }
    }

    /**
     * 路由一次 surface/item 请求；包或 item 未注册时报 HydroException。
     * 调用插件时不持锁，避免插件阻塞拖住整个注册表。
     */
    @Throws(HydroException::class)
    fun dispatch(packageId: String, surface: String, item: String, req: Request): View? {
        val p = lookup(packageId)
            ?: throw HydroException("hydrokit: 未知插件包 \"$packageId\"")
        val m = p.manifest()
        when (surface) {
            Surface.APP -> if (!Manifests.hasApp(m, item)) {
                throw HydroException("hydrokit: 插件 \"$packageId\" 没有 app \"$item\"")
            }
            Surface.WIDGET -> if (!Manifests.hasWidget(m, item)) {
                throw HydroException("hydrokit: 插件 \"$packageId\" 没有 widget \"$item\"")
            }
            else -> throw HydroException("hydrokit: 未知 surface \"$surface\"")
        }
        return p.view(surface, item, req)
    }
}

/** 启动器面向的插件摘要（runtime.go PluginSummary）。 */
data class PluginSummary(
    val pkg: Package,
    val apps: List<Item>,
    val widgets: List<Item>,
)
