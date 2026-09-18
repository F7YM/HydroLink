package com.hydro.link.server.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hydro.link.server.server.HydroHttpServer
import com.hydro.link.server.service.ServerService
import java.io.File

/**
 * 插件管理页的一行数据（主界面的「插件」Tab 与 [PluginsActivity] 共用）。
 */
data class PluginRow(
    val id: String,
    val name: String,
    val version: String,
    val runtime: String,
    val items: String,
    val permissions: String,
    val hasWeb: Boolean,
    val workerOnline: Boolean,
    val error: String?,
)

/**
 * 插件管理里与界面无关的那部分：取列表、安装、卸载、打开 WebUI。
 *
 * 主界面「插件」Tab 和设置里的 [PluginsActivity] 用同一份实现——两个入口
 * 各写一遍是最容易长歪的地方（改了一边忘了另一边，用户看到的行为就不一致）。
 */
object PluginUi {

    /** 当前服务实例（服务没跑时为 null，插件页据此显示提示）。 */
    fun server(): HydroHttpServer? = ServerService.instance?.server()

    /** 读取已安装插件列表（含 worker 在线状态）。 */
    fun rows(srv: HydroHttpServer): List<PluginRow> {
        val kit = srv.hydroKit
        return kit.store.list().map { info ->
            PluginRow(
                id = info.id,
                name = info.name.ifBlank { info.id },
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
     * 安装一个 .hpk：先复制到缓存目录（SAF 的 uri 不能当文件用），再在后台线程解压
     * 安装。回调在主线程，参数是给用户看的一句话。
     */
    fun install(activity: Activity, uri: Uri, onResult: (String) -> Unit) {
        val srv = server()
        if (srv == null) {
            onResult("服务未运行，无法安装")
            return
        }
        Thread {
            val tmp = File(activity.cacheDir, "install-${System.currentTimeMillis()}.hpk")
            val message = try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("无法读取所选文件")
                val info = srv.hydroKit.install(tmp)
                "已安装 ${info.name} v${info.version}"
            } catch (e: Exception) {
                "安装失败: ${e.message}"
            } finally {
                tmp.delete()
            }
            activity.runOnUiThread { onResult(message) }
        }.start()
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

    /** 打开插件 WebUI（同源 WebView，插件无需为安卓写第二套页面）。 */
    fun openWebUi(context: Context, id: String, name: String) {
        context.startActivity(PluginWebUiActivity.intent(context, id, name))
    }
}
