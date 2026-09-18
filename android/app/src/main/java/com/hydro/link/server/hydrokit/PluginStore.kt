package com.hydro.link.server.hydrokit

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 插件包仓库（对应 desktop/internal/store/plugins.go），语义与桌面对齐：
 *
 *   · 每个插件装在 <root>/plugins/<package-id>/ 下
 *   · 安装包是 zip（.hpk/.zip），允许整体套一层顶层目录
 *   · 同版本重装 / 更高版本更新 允许；**降级拒绝**
 *   · runtime=wasm 的入口必须是 .wasm（默认 plugin.wasm），否则默认 server.js
 *   · 卸载只删插件目录，插件存储（<root>/plugins-storage/<id>.json）保留
 *
 * 纯 JVM 实现（只用 java.io/java.util.zip），可在单测里指向临时目录直接跑。
 */
class PluginStore(private val root: File) {

    private val gson = Gson()

    val pluginsDir: File get() = File(root, "plugins").also { it.mkdirs() }
    private val storageDir: File get() = File(root, "plugins-storage").also { it.mkdirs() }

    /** 已安装插件的信息（对应 PluginInfo）。 */
    data class Installed(
        val id: String,
        val name: String,
        val version: String,
        val entry: String,
        val runtime: String,
        val apps: List<Item>,
        val widgets: List<Item>,
        val permissions: List<String>,
        val dir: File,
        val hasWeb: Boolean,
        val error: String? = null,
    )

    /** 安装包里读出的清单（对应 store.PluginManifest）。 */
    private data class RawManifest(
        val id: String? = null,
        val name: String? = null,
        val version: String? = null,
        val runtime: String? = null,
        val entry: String? = null,
        val apps: List<Item>? = null,
        val widgets: List<Item>? = null,
        val permissions: List<String>? = null,
    ) {
        fun toManifest() = Manifest(
            pkg = Package(id = id.orEmpty(), name = name.orEmpty(), version = version.orEmpty()),
            apps = apps ?: emptyList(),
            widgets = widgets ?: emptyList(),
            permissions = permissions ?: emptyList(),
            runtime = runtime,
            entry = entry,
        )
    }

    companion object {
        const val RUNTIME_JS = "js"
        const val RUNTIME_WASM = "wasm"

        private val ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,120}$")

        /** 版本比较（与桌面 CompareVersions 行为一致）。 */
        fun compareVersions(a: String, b: String): Int {
            val asg = a.trim().split(".")
            val bsg = b.trim().split(".")
            val n = maxOf(asg.size, bsg.size)
            for (i in 0 until n) {
                val av = asg.getOrElse(i) { "0" }
                val bv = bsg.getOrElse(i) { "0" }
                val c = compareSegment(av, bv)
                if (c != 0) return c
            }
            return 0
        }

        /** 单段比较：能当数字就按数字；否则数字前缀决定，带后缀（0-beta）的算更旧。 */
        private fun compareSegment(av: String, bv: String): Int {
            val an = av.toIntOrNull()
            val bn = bv.toIntOrNull()
            if (an != null && bn != null) return an.compareTo(bn)
            val ap = leadingInt(av)
            val bp = leadingInt(bv)
            if (ap != null && bp != null) {
                if (ap != bp) return ap.compareTo(bp)
                val aSuffix = av.length > ap.toString().length
                val bSuffix = bv.length > bp.toString().length
                if (aSuffix != bSuffix) return if (aSuffix) -1 else 1
            }
            return av.compareTo(bv)
        }

        private fun leadingInt(s: String): Int? {
            val digits = s.takeWhile { it.isDigit() }
            return if (digits.isEmpty()) null else digits.toIntOrNull()
        }

        fun normalizeRuntime(runtime: String?): String =
            if (runtime?.lowercase() == RUNTIME_WASM) RUNTIME_WASM else RUNTIME_JS
    }

    // ---- 查询 ----

    /** 列出所有已安装插件（坏插件以 error 形式列出，不影响其它插件）。 */
    fun list(): List<Installed> {
        val dirs = pluginsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.sortedBy { it.name }.mapNotNull { dir ->
            try {
                infoFor(dir)
            } catch (e: Exception) {
                Installed(
                    id = dir.name, name = dir.name, version = "", entry = "", runtime = RUNTIME_JS,
                    apps = emptyList(), widgets = emptyList(), permissions = emptyList(),
                    dir = dir, hasWeb = false, error = e.message ?: e.toString(),
                )
            }
        }
    }

    fun info(id: String): Installed? {
        val dir = File(pluginsDir, id)
        if (!dir.isDirectory) return null
        return infoFor(dir)
    }

    private fun infoFor(dir: File): Installed {
        val m = readManifest(dir)
        val runtime = normalizeRuntime(m.runtime)
        val entry = m.entry ?: if (runtime == RUNTIME_WASM) "plugin.wasm" else "server.js"
        return Installed(
            id = m.pkg.id.ifBlank { dir.name },
            name = m.pkg.name.ifBlank { m.pkg.id.ifBlank { dir.name } },
            version = m.pkg.version,
            entry = entry,
            runtime = runtime,
            apps = m.apps,
            widgets = m.widgets,
            permissions = m.permissions,
            dir = dir,
            hasWeb = File(dir, "web/index.html").isFile,
        )
    }

    /**
     * 读取插件目录里的 manifest.json。
     *
     * 注意形状差异：**包内**的 manifest.json 是扁平的
     * （`{"id":…,"name":…,"version":…,"apps":[…]}`，对应桌面的
     * store.PluginManifest）；而**协议**里的清单是嵌套的
     * （`{"package":{"id":…}}`，对应 hydrokit.Manifest）。这里读的是前者，
     * 再转换成本项目内部统一使用的 Manifest。混用会报 "缺少 package.id"。
     */
    @Throws(HydroException::class)
    fun readManifest(dir: File): Manifest {
        val f = File(dir, "manifest.json")
        if (!f.isFile) throw HydroException("缺少 manifest.json")
        return parsePackageManifest(f.readText())
    }

    /** 解析扁平包清单并做统一校验（含 entry/runtime 默认值与 wasm 约束）。 */
    @Throws(HydroException::class)
    fun parsePackageManifest(json: String): Manifest {
        val raw = try {
            gson.fromJson(json, RawManifest::class.java) ?: RawManifest()
        } catch (e: JsonSyntaxException) {
            throw HydroException("manifest.json 格式错误: ${e.message}")
        }
        val id = raw.id.orEmpty()
        if (!ID_RE.matches(id)) throw HydroException("无效的包 ID: \"$id\"")
        val runtime = normalizeRuntime(raw.runtime)
        val entry = raw.entry?.takeIf { it.isNotBlank() }
            ?: if (runtime == RUNTIME_WASM) "plugin.wasm" else "server.js"
        if (runtime == RUNTIME_WASM && !entry.lowercase().endsWith(".wasm")) {
            throw HydroException("runtime=wasm 的入口必须是 .wasm 文件: $entry")
        }
        val manifest = raw.copy(entry = entry, runtime = runtime).toManifest()
        Manifests.validate(manifest)
        return manifest
    }

    @Throws(HydroException::class)
    fun manifest(id: String): Manifest {
        val dir = File(pluginsDir, id)
        if (!dir.isDirectory) throw HydroException("插件不存在: $id")
        return readManifest(dir)
    }

    /** 读取插件目录内的相对路径文件（防逃逸）。 */
    fun readFile(id: String, relPath: String): ByteArray? {
        val dir = File(pluginsDir, id)
        val target = safeResolve(dir, relPath) ?: return null
        return if (target.isFile) target.readBytes() else null
    }

    fun entryCode(id: String): String? {
        val info = info(id) ?: return null
        return readFile(id, info.entry)?.toString(Charsets.UTF_8)
    }

    // ---- 安装 / 卸载 ----

    /**
     * 只读安装包清单（安装确认弹窗用）：不落盘、不检查版本。
     * 与 [install] 用同一套"顶层目录前缀 + manifest.json"规则，避免两边判断不一致。
     */
    @Throws(HydroException::class)
    fun inspect(hpk: File): Manifest {
        val ext = hpk.extension.lowercase()
        if (ext != "hpk" && ext != "zip") throw HydroException("只支持 .hpk / .zip 安装包")
        if (!hpk.isFile) throw HydroException("安装包不存在: ${hpk.path}")
        ZipFile(hpk).use { zip ->
            val entries = zip.entries().toList()
            val prefix = commonPrefix(entries)
            val manifestEntry = findEntry(entries, prefix, "manifest.json")
                ?: throw HydroException("安装包缺少 manifest.json")
            val raw = zip.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            return parsePackageManifest(raw)
        }
    }

    /**
     * 安装一个 .hpk / .zip 包。返回安装后的信息。
     * 与桌面端一致：同版本重装或升级允许，降级抛错；替换前先删旧目录。
     */
    @Throws(HydroException::class)
    fun install(hpk: File): Installed {
        val ext = hpk.extension.lowercase()
        if (ext != "hpk" && ext != "zip") throw HydroException("只支持 .hpk / .zip 安装包")
        if (!hpk.isFile) throw HydroException("安装包不存在: ${hpk.path}")

        ZipFile(hpk).use { zip ->
            val entries = zip.entries().toList()
            val prefix = commonPrefix(entries)
            val manifestEntry = findEntry(entries, prefix, "manifest.json")
                ?: throw HydroException("安装包缺少 manifest.json")

            val raw = zip.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            val manifest = parsePackageManifest(raw)
            val id = manifest.pkg.id
            val entry = manifest.entry ?: "server.js"
            if (findEntry(entries, prefix, entry) == null) {
                throw HydroException("安装包缺少入口文件 $entry")
            }

            val target = File(pluginsDir, id)
            // 降级保护
            val existing = if (target.isDirectory) runCatching { readManifest(target) }.getOrNull() else null
            if (existing != null && existing.pkg.version.isNotBlank() && manifest.pkg.version.isNotBlank()) {
                if (compareVersions(manifest.pkg.version, existing.pkg.version) < 0) {
                    throw HydroException(
                        "不允许降级：已安装 v${existing.pkg.version}，安装包为 v${manifest.pkg.version}"
                    )
                }
            }

            // 解压到 plugins/ 下的临时目录，再整体替换（同卷 rename，避免半成品）
            val tmp = File.createTempFile(".install-$id-", "", pluginsDir).let {
                it.delete(); it.mkdirs(); it
            }
            try {
                extract(zip, entries, prefix, tmp)
                if (!File(tmp, "manifest.json").isFile) throw HydroException("解压后缺少 manifest.json")
                if (!File(tmp, entry).isFile) throw HydroException("解压后缺少入口文件 $entry")
                if (target.exists() && !target.deleteRecursively()) {
                    throw HydroException("替换旧版本失败")
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyRecursively(target, overwrite = true)
                    tmp.deleteRecursively()
                }
            } catch (e: Exception) {
                tmp.deleteRecursively()
                throw if (e is HydroException) e else HydroException("安装失败: ${e.message}")
            }
            return info(id) ?: throw HydroException("安装后读取插件信息失败")
        }
    }

    /** 卸载插件目录；**保留**插件存储（与桌面端一致）。 */
    @Throws(HydroException::class)
    fun uninstall(id: String): Boolean {
        if (!ID_RE.matches(id)) throw HydroException("无效的插件 ID: \"$id\"")
        val target = File(pluginsDir, id)
        if (target.parentFile?.canonicalPath != pluginsDir.canonicalPath) {
            throw HydroException("无效的插件路径")
        }
        if (!target.isDirectory) throw HydroException("插件不存在: $id")
        return target.deleteRecursively()
    }

    // ---- 插件存储（scope: server；手表侧存储由容器保存）----

    private fun storageFile(id: String) = File(storageDir, "$id.json")

    @Synchronized
    fun storeAll(id: String): MutableMap<String, Any?> {
        val f = storageFile(id)
        if (!f.isFile) return mutableMapOf()
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(f.readText(), Map::class.java) as? MutableMap<String, Any?>) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    /** 读一个键：found=false 表示没有这个键（与桌面 store get 语义一致）。 */
    @Synchronized
    fun storeGet(id: String, key: String): Pair<Boolean, Any?> {
        val all = storeAll(id)
        return if (all.containsKey(key)) true to all[key] else false to null
    }

    @Synchronized
    fun storeSet(id: String, key: String, value: Any?) {
        val all = storeAll(id)
        all[key] = value
        writeStore(id, all)
    }

    @Synchronized
    fun storeRemove(id: String, key: String) {
        val all = storeAll(id)
        all.remove(key)
        writeStore(id, all)
    }

    private fun writeStore(id: String, all: Map<String, Any?>) {
        storageFile(id).writeText(gson.toJson(all))
    }

    // ---- zip 细节 ----

    private fun commonPrefix(entries: List<ZipEntry>): String {
        // 若所有条目都在同一个顶层目录下，就把这层剥掉（打包时常见）
        var prefix: String? = null
        for (e in entries) {
            val name = e.name
            if (name.isBlank() || name.startsWith("__MACOSX/") || name == ".DS_Store") continue
            val slash = name.indexOf('/')
            val top = if (slash < 0) "" else name.substring(0, slash)
            if (prefix == null) {
                prefix = top
                if (top.isEmpty()) return ""
            } else if (prefix != top) {
                return ""
            }
        }
        return prefix ?: ""
    }

    private fun findEntry(entries: List<ZipEntry>, prefix: String, rel: String): ZipEntry? {
        val want = if (prefix.isEmpty()) rel else "$prefix/$rel"
        return entries.firstOrNull { it.name == want }
    }

    @Throws(HydroException::class)
    private fun extract(zip: ZipFile, entries: List<ZipEntry>, prefix: String, dest: File) {
        for (e in entries) {
            val rel = if (prefix.isEmpty()) e.name else e.name.removePrefix("$prefix/")
            if (rel.isEmpty() || rel.startsWith("__MACOSX/") || rel == ".DS_Store") continue
            val target = safeResolve(dest, rel) ?: throw HydroException("安装包包含不安全路径: ${e.name}")
            if (e.isDirectory) {
                target.mkdirs()
                continue
            }
            target.parentFile?.mkdirs()
            zip.getInputStream(e).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
    }

    /** 把相对路径解析到 base 之内；越界返回 null。 */
    private fun safeResolve(base: File, rel: String): File? {
        val clean = rel.replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) return null
        val target = File(base, clean)
        val basePath = base.canonicalPath + File.separator
        val targetPath = target.canonicalPath
        return if (targetPath.startsWith(basePath)) target else null
    }
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> {
    val out = mutableListOf<T>()
    while (hasMoreElements()) out.add(nextElement())
    return out
}
