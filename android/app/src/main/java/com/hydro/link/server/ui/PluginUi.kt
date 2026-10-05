package com.hydro.link.server.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hydro.link.server.R
import com.hydro.link.server.hydrokit.Manifest
import com.hydro.link.server.server.HydroHttpServer
import com.hydro.link.server.service.ServerService
import java.io.File

/**
 * 插件管理页的一行数据（主界面「插件」Tab 与 [PluginsActivity] 共用）。
 * 列表里只显示 [name] + [pkgLine]，其余信息点卡片弹窗看。
 */
data class PluginRow(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val runtime: String,
    val items: String,
    val permissions: String,
    val hasWeb: Boolean,
    val workerOnline: Boolean,
    val error: String?,
) {
    /** 副标题：作者 · 包名 · 版本。 */
    val pkgLine: String get() = buildString {
        if (author.isNotBlank()) append(author).append(" · ")
        append(id)
        if (version.isNotBlank()) append(" · v").append(version)
    }
}

/**
 * 插件管理里与界面无关的那部分：取列表、安装（带确认弹窗）、卸载、打开 WebUI、详情弹窗。
 *
 * 主界面「插件」Tab 与设置里的 [PluginsActivity] 共用同一份实现——两个入口各写一遍最
 * 容易长歪。安装/卸载弹窗对齐桌面端：安装前把包名/版本/运行时/应用组件/权限列清楚并给出
 * 安全提醒，卸载二次确认。
 */
object PluginUi {

    /** 当前服务实例（服务没跑时为 null，插件页据此显示提示）。 */
    fun server(): HydroHttpServer? = ServerService.instance?.server()

    /** 读取已安装插件列表。 */
    fun rows(srv: HydroHttpServer): List<PluginRow> {
        val kit = srv.hydroKit
        return kit.store.list().map { info ->
            PluginRow(
                id = info.id,
                name = info.name.ifBlank { info.id },
                author = info.author,
                version = info.version,
                runtime = info.runtime,
                items = buildString {
                    if (info.apps.isNotEmpty()) {
                        append("应用 ").append(info.apps.joinToString("/") { it.name.ifBlank { it.id } })
                    }
                    if (info.apps.isNotEmpty() && info.widgets.isNotEmpty()) append(" · ")
                    if (info.widgets.isNotEmpty()) {
                        append("组件 ").append(info.widgets.joinToString("/") { it.name.ifBlank { it.id } })
                    }
                },
                permissions = info.permissions.joinToString(", "),
                hasWeb = info.hasWeb,
                workerOnline = kit.bridge.isConnected(info.id),
                error = info.error,
            )
        }
    }

    /** 「安装 .hpk」的文件选择器 Intent（SAF，任何位置都能选）。 */
    fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip"))
    }

    /**
     * 安装流程：选中文件 → 读清单（不落盘）→ **确认弹窗**（形态同桌面端）→ 安装。
     * 回调在主线程，参数是给用户看的一句话。
     */
    fun pickAndInstall(activity: Activity, uri: Uri, onResult: (String) -> Unit) {
        val srv = server()
        if (srv == null) {
            onResult("服务未运行，无法安装")
            return
        }
        Thread {
            val staged = File(activity.cacheDir, "install-${System.currentTimeMillis()}.hpk")
            val manifest: Manifest
            try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("无法读取所选文件")
                manifest = srv.hydroKit.store.inspect(staged)
            } catch (e: Exception) {
                staged.delete()
                activity.runOnUiThread { onResult("读取安装包失败: ${e.message}") }
                return@Thread
            }
            activity.runOnUiThread {
                showInstallConfirm(activity, manifest) {
                    Thread {
                        val message = try {
                            val info = srv.hydroKit.install(staged)
                            "已安装 ${info.name} v${info.version}"
                        } catch (e: Exception) {
                            "安装失败: ${e.message}"
                        } finally {
                            staged.delete()
                        }
                        activity.runOnUiThread { onResult(message) }
                    }.start()
                }
            }
        }.start()
    }

    /** 安装确认弹窗：清单摘要 + 权限 + 安全提醒。 */
    private fun showInstallConfirm(activity: Activity, m: Manifest, onConfirm: () -> Unit) {
        val hasInput = m.permissions.any { it.equals("input", ignoreCase = true) }
        val perms = if (m.permissions.isEmpty()) "未申请任何权限" else m.permissions.joinToString(", ")
        val items = buildString {
            m.apps.forEach { append("应用 ").append(it.name.ifBlank { it.id }).append("  ") }
            m.widgets.forEach { append("组件 ").append(it.name.ifBlank { it.id }).append("  ") }
        }.trim()
        val msg = buildString {
            append("包名：").append(m.pkg.id).append('\n')
            append("版本：v").append(m.pkg.version).append('\n')
            append("运行时：").append(m.runtime ?: "js")
            append("（").append(m.entry ?: "server.js").append("）").append('\n')
            if (items.isNotEmpty()) append("包含：").append(items).append('\n')
            append("权限：").append(perms).append('\n')
            append('\n')
            append("插件代码以本应用同等权限运行，可访问本机服务与数据目录，请只安装可信来源的插件。")
        }
        val dangerWarning = if (hasInput) {
            "高危权限警示：该插件申请了「屏幕触控与指针模拟 (input)」高危权限，可在后台模拟点击、滑动及按键操作。请确保你信任此插件且此插件来源安全可靠。"
        } else null
        val countdown = if (hasInput) 3 else 0

        EngineDialog.confirm(
            activity = activity,
            title = "安装 ${m.pkg.name.ifBlank { m.pkg.id }}",
            message = msg,
            positive = "安装",
            negative = "取消",
            onConfirm = onConfirm,
            countdownSeconds = countdown,
            dangerWarning = dangerWarning,
        )
    }

    /** 卸载插件（服务端存储保留，与桌面一致）。回调在主线程，参数是提示语。 */
    fun uninstall(activity: Activity, id: String, name: String, onResult: (String) -> Unit) {
        val srv = server()
        if (srv == null) {
            onResult("服务未运行，无法卸载")
            return
        }
        Thread {
            val message = try {
                srv.hydroKit.uninstall(id)
                "已卸载 $name"
            } catch (e: Exception) {
                "卸载失败: ${e.message}"
            }
            activity.runOnUiThread { onResult(message) }
        }.start()
    }

    /** 卸载二次确认。 */
    fun confirmUninstall(activity: Activity, id: String, name: String, onResult: (String) -> Unit) {
        // 回调显式命名给 onConfirm：写成尾随 lambda 会落到否定钮上（点「取消」反而卸载）
        EngineDialog.confirm(
            activity = activity,
            title = "卸载 $name",
            message = "确定卸载「$name」吗？\n插件的服务端存储会保留，重装后设置还在。",
            positive = "卸载",
            negative = "取消",
            onConfirm = { uninstall(activity, id, name, onResult) },
        )
    }

    /** 点插件卡片时的详情弹窗：列表里放不下的信息都在这。 */
    fun showDetails(activity: Activity, row: PluginRow) {
        val msg = buildString {
            append("作者：").append(row.author.ifBlank { "—" }).append('\n')
            append("包名：").append(row.id).append('\n')
            append("版本：").append(if (row.version.isBlank()) "—" else "v${row.version}").append('\n')
            append("运行时：").append(row.runtime).append('\n')
            if (row.items.isNotBlank()) append("包含：").append(row.items).append('\n')
            append("权限：").append(if (row.permissions.isBlank()) "未申请权限" else row.permissions)
            if (row.error != null) append('\n').append("清单异常：").append(row.error)
        }
        EngineDialog.confirm(activity, row.name, msg, "知道了", null, null)
    }

    /** 打开插件 WebUI（同源 WebView，插件无需为安卓写第二套页面）。 */
    fun openWebUi(context: Context, id: String, name: String) {
        context.startActivity(PluginWebUiActivity.intent(context, id, name))
    }
}
