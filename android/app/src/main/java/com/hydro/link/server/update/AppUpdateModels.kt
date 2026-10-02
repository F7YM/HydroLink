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
            if (clElem.isJsonArray) {
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

        return AppUpdateInfo(
            version = version,
            versionCode = versionCode,
            publishDate = publishDate,
            title = title,
            changelog = changelogList,
            downloadUrl = downloadUrl,
            backupUrl = backupUrl,
            password = password,
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

    /** 格式化更新日志列表。 */
    fun formatChangelog(changelog: List<String>): String = buildString {
        if (changelog.isEmpty()) {
            append("常规稳定性修复与性能优化。")
            return@buildString
        }
        changelog.forEachIndexed { index, line ->
            // 如果日志本身已带序号或项目符号则保留，否则补充序号
            val trimmed = line.trim()
            if (trimmed.startsWith("-") || trimmed.startsWith("•") || Regex("""^\d+[.、]""").containsMatchIn(trimmed)) {
                append(trimmed)
            } else {
                append("${index + 1}. ").append(trimmed)
            }
            if (index < changelog.size - 1) append('\n')
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
