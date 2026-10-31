package com.hydro.link.server.plugin

import com.hydro.link.server.store.Store
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * 插件包清单（**扁平**形状，对应 Go `store.PluginManifest`）。
 *
 * 注意别和协议里的清单搞混：协议里的是嵌套的 `{"package":{"id":…}}`（`hydrokit.Manifest`），
 * 包内 manifest.json 是扁平的 `{"id":…}`。两种形状混用会报「缺少 id」。
 */
@Serializable
data class PluginManifest(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("author") val author: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("entry") val entry: String = "",
    @SerialName("runtime") val runtime: String = "js",
    @SerialName("minHydroLink") val minHydroLink: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("apps") val apps: List<Item> = emptyList(),
    @SerialName("widgets") val widgets: List<Item> = emptyList(),
    @SerialName("permissions") val permissions: List<String> = emptyList(),
)

/** 清单里的一个应用/组件项。 */
@Serializable
data class Item(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("icon") val icon: String = "",
    @SerialName("refreshMs") val refreshMs: Long = 0,
)

/** 已安装插件的信息（`store.PluginInfo`）。 */
data class PluginInfo(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val entry: String,
    val runtime: String,
    val apps: List<Item>,
    val widgets: List<Item>,
    val permissions: List<String>,
    val dir: File,
) {
    /** 是否有自带 WebUI 页面（`web/index.html`）。 */
    val hasWeb: Boolean get() = File(dir, "web/index.html").isFile
}

/**
 * 插件包安装/卸载（对应 Go `internal/store/plugins.go`）。
 *
 * 关键语义照抄 Go：
 * - 装之前先读包内 manifest，**禁止降级**（同版本重装允许）
 * - 解压到临时目录 → 校验 `manifest.json` 与入口文件都在 → 删旧目录 → rename 落位
 *   （插件存储在 `plugins-storage/` 下，不受替换影响）
 * - 解压时**拒绝路径逃逸**（`../` 之类）
 * - 多个 zip 条目共有的顶层前缀会被剥掉（打包时多套一层目录的情况）
 */
class PluginStore(private val store: Store, private val hostVersion: String = "1.3.0") {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun pluginsDir(): File = store.pluginsDir

    /** 列出全部已安装插件（按 id 排序，与 Go 的 `ListPlugins` 一致）。 */
    fun listPlugins(): List<PluginInfo> {
        val dir = store.pluginsDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { readInstalled(it) }
            .sortedBy { it.id }
    }

    /** 读一个已安装目录里的清单。 */
    fun readInstalled(dir: File): PluginInfo? {
        val manifest = readManifest(File(dir, "manifest.json")) ?: return null
        return PluginInfo(
            id = manifest.id.ifBlank { dir.name },
            name = manifest.name.ifBlank { manifest.id.ifBlank { dir.name } },
            author = manifest.author,
            version = manifest.version,
            entry = manifest.entry,
            runtime = manifest.runtime,
            apps = manifest.apps,
            widgets = manifest.widgets,
            permissions = manifest.permissions,
            dir = dir,
        )
    }

    fun manifestOf(id: String): PluginManifest? =
        readManifest(File(File(store.pluginsDir, id), "manifest.json"))

    /** 安装一个 .hpk。失败时抛 [IllegalArgumentException]，消息与 Go 侧一致。 */
    fun install(zipPath: String): PluginInfo {
        val zipFile = File(zipPath)
        if (!zipFile.isFile) throw IllegalArgumentException("安装包不存在: $zipPath")

        val manifest = readManifestFromZip(zipFile)
            ?: throw IllegalArgumentException("安装包缺少 manifest.json")
        if (manifest.id.isBlank()) throw IllegalArgumentException("manifest 缺少 id")
        if (manifest.entry.isBlank()) throw IllegalArgumentException("manifest 缺少 entry")
        val need = manifest.minHydroLink.trim()
        if (need.isNotEmpty() && compareVersions(hostVersion, need) < 0) {
            throw IllegalArgumentException("该插件需要 HydroLink ≥ $need，当前 $hostVersion")
        }

        val target = File(store.pluginsDir, manifest.id)
        val existing = readManifest(File(target, "manifest.json"))
        if (existing != null && existing.version.isNotBlank()) {
            if (compareVersions(manifest.version, existing.version) < 0) {
                throw IllegalArgumentException(
                    "不允许降级：已安装 v${existing.version}，安装包为 v${manifest.version}"
                )
            }
        }

        store.pluginsDir.mkdirs()
        val tmp = File.createTempFile(".tmp-${manifest.id}-", "", store.pluginsDir)
        tmp.delete()
        tmp.mkdirs()
        try {
            val prefix = commonPrefix(zipFile)
            extract(zipFile, prefix, tmp)
            if (!File(tmp, "manifest.json").isFile) {
                throw IllegalArgumentException("解压后缺少 manifest.json")
            }
            if (!File(tmp, manifest.entry).isFile) {
                throw IllegalArgumentException("解压后缺少入口文件 ${manifest.entry}")
            }
            // 更新/重装：先删旧目录再落位（插件存储不受影响）
            if (target.exists()) {
                if (!target.deleteRecursively()) {
                    throw IllegalArgumentException("替换旧版本失败")
                }
            }
            if (!tmp.renameTo(target)) {
                // 跨文件系统时 rename 会失败，退化成复制
                target.mkdirs()
                tmp.copyRecursively(target, overwrite = true)
                tmp.deleteRecursively()
            }
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }

        return readInstalled(target) ?: throw IllegalArgumentException("安装后读不到清单")
    }

    /** 卸载（插件存储 `plugins-storage/<id>.json` 保留，与安卓端一致）。 */
    fun uninstall(id: String) {
        val dir = File(store.pluginsDir, id)
        if (dir.exists() && !dir.deleteRecursively()) {
            throw IllegalArgumentException("卸载失败: $id")
        }
    }

    // ------------------------------------------------------------ 内部

    private fun readManifest(file: File): PluginManifest? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(PluginManifest.serializer(), file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun readManifestFromZip(zipFile: File): PluginManifest? = try {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("manifest.json")
                ?: zip.entries().toList().firstOrNull { it.name.endsWith("/manifest.json") }
                ?: return null
            val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
            json.decodeFromString(PluginManifest.serializer(), text)
        }
    } catch (e: Exception) {
        null
    }

    /** zip 条目共有的顶层目录（打包时多套一层的情况），没有就返回空串。 */
    private fun commonPrefix(zipFile: File): String = ZipFile(zipFile).use { zip ->
        val names = zip.entries().toList().map { it.name }.filter { !it.endsWith("/") }
        if (names.isEmpty()) return@use ""
        val first = names.first().substringBefore('/')
        val shared = names.all { it.startsWith("$first/") }
        if (shared && names.any { it.count { c -> c == '/' } > 1 }) "$first/" else ""
    }

    /** 解压，拒绝路径逃逸（`../` 之类写到目标目录外面去）。 */
    private fun extract(zipFile: File, prefix: String, target: File) {
        val targetPath = target.canonicalPath
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries().toList()) {
                if (entry.isDirectory) continue
                var name = entry.name
                if (prefix.isNotEmpty() && name.startsWith(prefix)) name = name.removePrefix(prefix)
                if (name.isEmpty()) continue
                if (name.startsWith("/") || name.contains("..")) {
                    throw IllegalArgumentException("安装包包含非法路径: ${entry.name}")
                }
                val out = File(target, name)
                if (!out.canonicalPath.startsWith("$targetPath${File.separator}")) {
                    throw IllegalArgumentException("安装包包含越界路径: ${entry.name}")
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    companion object {
        /**
         * 版本比较（与 Go `CompareVersions` 同语义）。
         *
         * 缺失段按 0（`1.0` == `1.0.0`）；带预发布后缀的段比正式段低
         * （`1.0.0-beta` < `1.0.0`）。
         */
        fun compareVersions(a: String, b: String): Int {
            val aParts = a.trim().split(".")
            val bParts = b.trim().split(".")
            val n = maxOf(aParts.size, bParts.size)
            for (i in 0 until n) {
                val av = if (i < aParts.size) aParts[i] else "0"
                val bv = if (i < bParts.size) bParts[i] else "0"
                val c = compareSegment(av, bv)
                if (c != 0) return c
            }
            return 0
        }

        private fun compareSegment(a: String, b: String): Int {
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            if (an != null && bn != null) return an.compareTo(bn)
            // 纯数字 < 预发布串（"1.0.0" > "1.0.0-beta"）
            if (an != null) return 1
            if (bn != null) return -1
            // 都有预发布后缀：按字典序，再按长度
            val c = a.compareTo(b)
            return if (c != 0) c else a.length.compareTo(b.length)
        }
    }
}
