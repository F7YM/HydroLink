package com.hydro.link.server.market

import com.hydro.link.server.hydrokit.PluginStore

/**
 * 「这个已装插件有没有新版」的一条结果。
 *
 * [entry] 是市场里那条（也就是新版本），[currentVersion] 是本地已装版本。
 */
data class PluginUpdate(
    val entry: MarketEntry,
    val currentVersion: String,
    /** 宿主版本够不够新：不够时更新按钮灰显，只让用户看更新日志。 */
    val compatible: Boolean,
)

/**
 * 更新检查：拿市场索引与本地已装列表比版本。
 *
 * 纯函数（可 JVM 单测），只认「索引版本 > 本地版本」这一条；禁降级仍由 [PluginStore]
 * 在真安装时兜底，这里不重复判断，避免两处规则漂移。
 */
object MarketUpdates {

    fun compute(
        index: MarketIndex?,
        installed: Map<String, String>,
        hostVersion: String,
    ): List<PluginUpdate> {
        if (index == null) return emptyList()
        return index.plugins.mapNotNull { entry ->
            val current = installed[entry.id] ?: return@mapNotNull null
            if (PluginStore.compareVersions(entry.version, current) <= 0) return@mapNotNull null
            PluginUpdate(
                entry = entry,
                currentVersion = current,
                compatible = hostVersionCovers(hostVersion, entry.minHydrolink),
            )
        }.sortedBy { it.entry.id }
    }

    /** 宿主版本是否满足插件的 `min_hydrolink`（空 = 不限）。 */
    fun hostVersionCovers(hostVersion: String, minHydroLink: String): Boolean {
        if (minHydroLink.isBlank()) return true
        return PluginStore.compareVersions(hostVersion, minHydroLink) >= 0
    }

    /** 更新弹窗正文：版本变化 + 大小/运行时/权限 + 更新日志；版本不够时写明差在哪。 */
    fun dialogText(update: PluginUpdate, base: String, hostVersion: String): String {
        val e = update.entry
        return buildString {
            append("新版本：v").append(e.version).append("（当前 v").append(update.currentVersion).append("）\n")
            append("运行时：").append(e.runtime).append('\n')
            append("大小：").append(e.size / 1024).append(" KB\n")
            append("权限：")
            append(if (e.permissions.isEmpty()) "未申请权限" else e.permissions.joinToString(", "))
            append('\n')
            append("来源：").append(base.ifEmpty { "—" }).append('\n')
            append("指纹：").append(e.sha256.take(12)).append('\n')
            append('\n')
            if (!update.compatible) {
                append("HydroLink 版本过低：需要 v").append(e.minHydrolink).append(" 及以上，当前 v")
                append(hostVersion).append("。升级 HydroLink 后才能装。\n\n")
            }
            append("更新日志：\n")
            append(e.notes.ifBlank { "这一版没有提供更新日志。" })
        }
    }
}
