package com.hydro.link.server.update

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hydro.link.server.hydrokit.PluginStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用版本更新元数据（对应 hydromarket-index/app.json）。
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

    private val gson = Gson()

    /**
     * 解析 app.json，兼容字符串或数组形式的 changelog。
     */
    fun parse(json: String): AppUpdateInfo {
        val obj = gson.fromJson(json, JsonObject::class.java)
            ?: throw IllegalArgumentException("无法解析 JSON")

        val version = obj.get("version")?.asString?.trim().orEmpty()
        if (version.isEmpty()) throw IllegalArgumentException("缺少 version 字段")

        val versionCode = obj.get("version_code")?.asInt ?: 0
        val publishDate = obj.get("publish_date")?.asString?.trim().orEmpty()
        val title = obj.get("title")?.asString?.trim().orEmpty()

        val changelogList = mutableListOf<String>()
        val clElem = obj.get("changelog")
        if (clElem != null) {
            if (clElem.isJsonObject) {
                // 支持按分类对象解析，如 "手机端"、"桌面端"、"手表端"
                clElem.asJsonObject.entrySet().forEach { (key, value) ->
                    val header = if (key.startsWith("【") && key.endsWith("】")) key else "【$key】"
                    changelogList.add(header)
                    if (value.isJsonArray) {
                        value.asJsonArray.forEach { item ->
                            val s = item.asString.trim()
                            if (s.isNotEmpty()) changelogList.add(s)
                        }
                    } else if (value.isJsonPrimitive) {
                        value.asString.lines().forEach { line ->
                            val s = line.trim()
                            if (s.isNotEmpty()) changelogList.add(s)
                        }
                    }
                }
            } else if (clElem.isJsonArray) {
                clElem.asJsonArray.forEach { elem ->
                    val s = elem.asString.trim()
                    if (s.isNotEmpty()) changelogList.add(s)
                }
            } else if (clElem.isJsonPrimitive) {
                clElem.asString.lines().forEach { line ->
                    val s = line.trim()
                    if (s.isNotEmpty()) changelogList.add(s)
                }
            }
        }

        val downloadUrl = obj.get("download_url")?.asString?.trim().orEmpty()
        val backupUrl = obj.get("backup_url")?.asString?.trim().orEmpty()
        val password = obj.get("password")?.asString?.trim().orEmpty()
        val force = obj.get("force")?.asBoolean ?: false
        val minVersion = obj.get("min_version")?.asString?.trim().orEmpty()

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

    /** 当前日期字符串（yyyy-MM-dd）。 */
    fun todayDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    /** 自动检查频控：每天最多检查 1 次。 */
    fun shouldAutoCheck(lastCheckDate: String, today: String = todayDateString()): Boolean =
        lastCheckDate != today

    /** 稍后提醒控制：今日不再提醒。 */
    fun shouldPrompt(lastIgnoreDate: String, today: String = todayDateString()): Boolean =
        lastIgnoreDate != today

    /** 判定远端版本是否高于当前本地版本（复用 PluginStore.compareVersions）。 */
    fun isNewer(remoteVersion: String, currentVersion: String): Boolean =
        PluginStore.compareVersions(remoteVersion, currentVersion) > 0

    /** 判定是否属于强制更新（force 标识为 true，或本地当前版本低于最低兼容版本 minVersion）。 */
    fun isForceUpdate(update: AppUpdateInfo, currentVersion: String): Boolean {
        if (update.force) return true
        if (update.minVersion.isNotBlank()) {
            return PluginStore.compareVersions(currentVersion, update.minVersion) < 0
        }
        return false
    }

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
