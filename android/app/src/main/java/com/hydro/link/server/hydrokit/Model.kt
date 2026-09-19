package com.hydro.link.server.hydrokit

import com.google.gson.annotations.SerializedName

/**
 * HydroKit 数据模型（Android 端）—— 与桌面端 Go 实现字节兼容。
 *
 * 对应关系（桌面 Go → Android Kotlin）：
 *   hydrokit/manifest.go → Manifest / Item / Package
 *   hydrokit/tree.go     → Node / View
 *   hydrokit/plugin.go   → Request / Event / Screen / Trigger*
 *
 * 协议权威描述见 docs/README.md；本文件只做数据结构与校验，
 * 不依赖任何 Android API（可在 JVM 单测里直接跑）。
 */

// ---- 组件类型（tree.go 的 Type* 常量）----

object NodeType {
    const val COLUMN = "column"
    const val ROW = "row"
    const val SCROLL = "scroll"
    const val CARD = "card"
    const val TEXT = "text"
    const val ICON = "icon"
    const val IMAGE = "image"
    const val SPINNER = "spinner"
    const val BADGE = "badge"
    const val BUTTON = "button"
    const val SWITCH = "switch"
    const val INPUT = "input"
}

/** 表面（surface）：app / widget / desktop。 */
object Surface {
    const val APP = "app"
    const val WIDGET = "widget"
    const val DESKTOP = "desktop"
}

/** 请求触发原因（plugin.go 的 Trigger* 常量）。 */
object Trigger {
    const val OPEN = "open"
    const val REFRESH = "refresh"
    const val TIMER = "timer"
    const val EVENT = "event"
}

/**
 * 组件树节点。字段名与协议一致（style/children/events/props/media）。
 *
 * 用 Map<String, Any?> 而不是强类型 Style/Props：插件可以自由扩展属性，
 * 容器按需读取（与 Go 侧的 map[string]any 等价）。
 */
data class Node(
    @SerializedName("type") val type: String,
    // 这些字段是 var：链式 helper 需要就地建/改 map，Gson 反序列化也更省心
    @SerializedName("style") var style: MutableMap<String, Any?>? = null,
    @SerializedName("children") var children: MutableList<Node>? = null,
    @SerializedName("events") var events: MutableMap<String, String>? = null,
    @SerializedName("props") var props: MutableMap<String, Any?>? = null,
    @SerializedName("media") var media: MutableMap<String, MutableMap<String, Any?>>? = null,
) {
    fun style(key: String, value: Any?): Node {
        val s = style ?: mutableMapOf<String, Any?>().also { style = it }
        s[key] = value
        return this
    }

    fun prop(key: String, value: Any?): Node {
        val p = props ?: mutableMapOf<String, Any?>().also { props = it }
        p[key] = value
        return this
    }

    fun on(event: String, handler: String): Node {
        val e = events ?: mutableMapOf<String, String>().also { events = it }
        e[event] = handler
        return this
    }

    /** 加入某屏幕形态（circle/rect/pill）的样式覆盖。 */
    fun media(tag: String, vararg pairs: Pair<String, Any?>): Node {
        val m = media ?: mutableMapOf<String, MutableMap<String, Any?>>().also { media = it }
        val forTag = m.getOrPut(tag) { mutableMapOf() }
        pairs.forEach { forTag[it.first] = it.second }
        return this
    }

    fun child(node: Node): Node {
        val c = children ?: mutableListOf<Node>().also { children = it }
        c.add(node)
        return this
    }

    fun add(vararg nodes: Node): Node {
        val c = children ?: mutableListOf<Node>().also { children = it }
        c.addAll(nodes)
        return this
    }

    /** 深拷贝（容器侧合并 media 覆盖时要避免改到插件给的树）。 */
    fun deepCopy(): Node = Node(
        type = type,
        style = style?.let { HashMap(it) },
        children = children?.map { it.deepCopy() }?.toMutableList(),
        events = events?.let { HashMap(it) },
        props = props?.let { HashMap(it) },
        media = media?.mapValues { HashMap(it.value) }?.toMutableMap(),
    )

    companion object {
        fun column(vararg children: Node) = Node(NodeType.COLUMN, children = children.toMutableList())
        fun row(vararg children: Node) = Node(NodeType.ROW, children = children.toMutableList())
        fun text(text: String) = Node(NodeType.TEXT, props = mutableMapOf("text" to text))
        fun button(label: String) = Node(NodeType.BUTTON, props = mutableMapOf("label" to label))
        fun icon(name: String) = Node(NodeType.ICON, props = mutableMapOf("name" to name))
    }
}

/**
 * 一次 surface 请求的返回：根树 + 可选刷新周期（毫秒）。
 * storage 用于把「手表侧存储」的写入带回容器落盘（docs/03-storage.md）。
 */
data class View(
    @SerializedName("refreshMs") val refreshMs: Int = 0,
    @SerializedName("root") val root: Node,
    @SerializedName("storage") val storage: MutableMap<String, Any?>? = null,
)

/** Manifest 里注册的一个 HydroApp / HydroWidget。 */
data class Item(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("refreshMs") val refreshMs: Int = 0,
)

/** 插件包标识。 */
data class Package(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("version") val version: String = "",
)

/**
 * 插件清单：注册哪些 app/widget、请求哪些权限。
 * runtime 字段是 Android 端新增的可选信息（js/wasm），桌面侧从入口文件名推断；
 * 解析时容错（缺省按 js 处理）。
 */
data class Manifest(
    @SerializedName("package") val pkg: Package = Package(),
    /** 作者（必填，仅展示信息，无签名）。 */
    @SerializedName("author") val author: String = "",
    /** 可选：项目地址。 */
    @SerializedName("url") val url: String? = null,
    /** 可选：要求的最低 HydroLink 版本。 */
    @SerializedName("minHydroLink") val minHydroLink: String? = null,
    @SerializedName("apps") val apps: List<Item> = emptyList(),
    @SerializedName("widgets") val widgets: List<Item> = emptyList(),
    @SerializedName("permissions") val permissions: List<String> = emptyList(),
    @SerializedName("runtime") val runtime: String? = null,
    @SerializedName("entry") val entry: String? = null,
) {
    val packageId: String get() = pkg.id
}

/** 用户交互事件（plugin.go Event）。 */
data class Event(
    @SerializedName("handler") val handler: String = "",
    @SerializedName("source") val source: String? = null,
    @SerializedName("id") val id: String? = null,
    @SerializedName("value") val value: Any? = null,
)

/**
 * 请求方屏幕信息（?shape=circle&width=466&height=466&dpr=2&dt=watch）。
 * shape 归一化为 rect | circle | pill。
 */
data class Screen(
    @SerializedName("shape") val shape: String? = null,
    @SerializedName("width") val width: Int = 0,
    @SerializedName("height") val height: Int = 0,
    @SerializedName("dpr") val dpr: Double = 0.0,
    @SerializedName("deviceType") val deviceType: String? = null,
)

/**
 * 一次 view 请求：为什么请求 + 事件 + 参数 + 屏幕 + 设备侧存储快照。
 * store 来自请求头 X-Hydro-Store。
 */
data class Request(
    @SerializedName("trigger") val trigger: String,
    @SerializedName("event") val event: Event? = null,
    @SerializedName("params") val params: MutableMap<String, String> = mutableMapOf(),
    @SerializedName("screen") val screen: Screen = Screen(),
    @SerializedName("store") val store: MutableMap<String, Any?>? = null,
    /**
     * 宿主平台信息（插件按平台分支用）：os / arch / app / sdk。
     * os ∈ linux|windows|darwin|android，两端同构；JS 读 req.host，WASM 读 ctx.req.host
     * 或 ctx.host（ABI 上下文里也放了一份，省得再往 req 里钻）。
     */
    @SerializedName("host") val host: MutableMap<String, String>? = null,
)
