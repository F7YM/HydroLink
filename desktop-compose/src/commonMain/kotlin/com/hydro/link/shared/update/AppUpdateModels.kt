package com.hydro.link.shared.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 桌面端应用版本更新元数据（与 hydromarket-index/app.json 对齐）。
 */
data class AppUpdateInfo(
    val version: String,
    val versionCode: Int = 0,
    val publishDate: String = "",
    val title: String = "",
    val changelog: List<String> = emptyList(),
    val downloadUrl: String = "",
    val backupUrl: String = "",
    val password: String = "",
    val force: Boolean = false,
    val minVersion: String = "",
)

object AppUpdateHelper {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 app.json，兼容字符串或数组形式的 changelog。 */
    fun parse(jsonText: String): AppUpdateInfo {
        val root = json.parseToJsonElement(jsonText).jsonObject

        val version = root["version"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (version.isEmpty()) throw IllegalArgumentException("缺少 version 字段")

        val versionCode = root["version_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val publishDate = root["publish_date"]?.jsonPrimitive?.content?.trim().orEmpty()
        val title = root["title"]?.jsonPrimitive?.content?.trim().orEmpty()

        val changelogList = mutableListOf<String>()
        val clElem = root["changelog"]
        if (clElem != null) {
            try {
                if (clElem is JsonObject) {
                    // 支持按分类对象解析，如 "手机端"、"桌面端"、"手表端"
                    clElem.forEach { (key, value) ->
                        val header = if (key.startsWith("【") && key.endsWith("】")) key else "【$key】"
                        changelogList.add(header)
                        try {
                            value.jsonArray.forEach { item ->
                                val s = item.jsonPrimitive.content.trim()
                                if (s.isNotEmpty()) changelogList.add(s)
                            }
                        } catch (_: Exception) {
                            value.jsonPrimitive.content.lines().forEach { line ->
                                val s = line.trim()
                                if (s.isNotEmpty()) changelogList.add(s)
                            }
                        }
                    }
                } else {
                    clElem.jsonArray.forEach { elem ->
                        val s = elem.jsonPrimitive.content.trim()
                        if (s.isNotEmpty()) changelogList.add(s)
                    }
                }
            } catch (_: Exception) {
                clElem.jsonPrimitive.content.lines().forEach { line ->
                    val s = line.trim()
                    if (s.isNotEmpty()) changelogList.add(s)
                }
            }
        }

        val downloadUrl = root["download_url"]?.jsonPrimitive?.content?.trim().orEmpty()
        val backupUrl = root["backup_url"]?.jsonPrimitive?.content?.trim().orEmpty()
        val password = root["password"]?.jsonPrimitive?.content?.trim().orEmpty()
        val force = root["force"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val minVersion = root["min_version"]?.jsonPrimitive?.content?.trim().orEmpty()

        return AppUpdateInfo(
            version = version,
            versionCode = versionCode,
            publishDate = publishDate,
            title = title,
            changelog = changelogList,
            downloadUrl = downloadUrl,
            backupUrl = backupUrl,
            password = password,
            force = force,
            minVersion = minVersion,
        )
    }

    /** 判定是否属于强制更新（force 标识为 true，或当前版本低于最低兼容版本 minVersion）。 */
    fun isForceUpdate(update: AppUpdateInfo, currentVersion: String): Boolean {
        if (update.force) return true
        if (update.minVersion.isNotBlank()) {
            return compareVersions(currentVersion, update.minVersion) < 0
        }
        return false
    }

    /** 语义化版本比对（与安卓端及桌面端插件版本比对逻辑一致）。 */
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
        if (an != null) return 1
        if (bn != null) return -1
        val c = a.compareTo(b)
        return if (c != 0) c else a.length.compareTo(b.length)
    }

    /** 判定远端版本是否高于当前本地版本。 */
    fun isNewer(remoteVersion: String, currentVersion: String): Boolean =
        compareVersions(remoteVersion, currentVersion) > 0

    /** 自动检查频控：每天最多检查 1 次。 */
    fun shouldAutoCheck(lastCheckDate: String, today: String): Boolean =
        lastCheckDate != today

    /** 稍后提醒控制：今日不再提醒。 */
    fun shouldPrompt(lastIgnoreDate: String, today: String): Boolean =
        lastIgnoreDate != today

    /** 是否属于分类标题行（如【手机端】、[桌面端]、### 手表端、手机端：等）。 */
    fun isSectionHeader(line: String): Boolean {
        val t = line.trim()
        return (t.startsWith("【") && t.endsWith("】")) ||
            (t.startsWith("[") && t.endsWith("]")) ||
            t.startsWith("###") ||
            t.startsWith("##") ||
            t.startsWith("#") ||
            (t.endsWith("：") && !t.contains(" ")) ||
            (t.endsWith(":") && !t.contains(" "))
    }

    /** 格式化更新日志列表。支持分类标题与多端独立序号计数。 */
    fun formatChangelog(changelog: List<String>): String = buildString {
        if (changelog.isEmpty()) {
            append("常规稳定性修复与性能优化。")
            return@buildString
        }

        var itemIndex = 1
        var isFirstLine = true

        changelog.forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEach

            if (isSectionHeader(trimmed)) {
                if (!isFirstLine) append("\n\n")
                append(trimmed)
                itemIndex = 1 // 每个分类独立计数
            } else {
                if (!isFirstLine) append('\n')
                if (trimmed.startsWith("-") || trimmed.startsWith("•") || Regex("""^\d+[.、]""").containsMatchIn(trimmed)) {
                    append(trimmed)
                } else {
                    append("$itemIndex. ").append(trimmed)
                    itemIndex++
                }
            }
            isFirstLine = false
        }
    }

    /** 生成更新弹窗正文。 */
    fun dialogText(update: AppUpdateInfo, currentVersion: String): String = buildString {
        append("最新版本：v").append(update.version).append("（当前 v").append(currentVersion).append("）\n")
        if (update.publishDate.isNotBlank()) {
            append("发布日期：").append(update.publishDate).append('\n')
        }
        if (update.password.isNotBlank()) {
            append("网盘提取码：").append(update.password).append("（前往时自动复制）\n")
        }
        append('\n')
        append("更新日志：\n")
        append(formatChangelog(update.changelog))
    }
}
