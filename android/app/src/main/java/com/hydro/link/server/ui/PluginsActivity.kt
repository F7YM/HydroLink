package com.hydro.link.server.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.hydro.link.server.R
import com.hydro.link.server.service.ServerService

/**
 * 插件管理页（Android 端的 HydroKit 入口）：
 *   · 列出已安装插件（名称/版本/runtime/注册的 app/widget/worker 在线状态）
 *   · 从文件选择器安装 .hpk（安装后**热注册**并自动拉起 worker 宿主）
 *   · 卸载（二次确认；插件服务端存储保留，与桌面一致）
 *   · 打开插件 WebUI（同源 WebView，见 [PluginWebUiActivity]）
 */
class PluginsActivity : AppCompatActivity() {

    private lateinit var adapter: PluginAdapter
    private val installLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        installFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugins)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { finish() }
        }

        adapter = PluginAdapter(
            onOpenWebUi = { info -> openWebUi(info.id, info.name) },
            onUninstall = { info -> confirmUninstall(info.id, info.name) },
        )
        findViewById<RecyclerView>(R.id.pluginList).apply {
            layoutManager = LinearLayoutManager(this@PluginsActivity)
            adapter = this@PluginsActivity.adapter
        }
        findViewById<View>(R.id.btnInstallPlugin).setOnClickListener { pickPackage() }
        findViewById<View>(R.id.btnRefreshPlugins).setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---- 列表 ----

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

    private fun server(): com.hydro.link.server.server.HydroHttpServer? =
        ServerService.instance?.server()

    private fun refresh() {
        val srv = server()
        if (srv == null) {
            adapter.submit(emptyList(), "服务未运行——请先回到首页启动服务")
            return
        }
        val kit = srv.hydroKit
        val rows = kit.store.list().map { info ->
            PluginRow(
                id = info.id,
                name = info.name.ifBlank { info.id },
                version = info.version,
                runtime = info.runtime,
                items = buildString {
                    if (info.apps.isNotEmpty()) append("应用 ").append(info.apps.joinToString("/") { it.name.ifBlank { it.id } })
                    if (info.apps.isNotEmpty() && info.widgets.isNotEmpty()) append(" · ")
                    if (info.widgets.isNotEmpty()) append("组件 ").append(info.widgets.joinToString("/") { it.name.ifBlank { it.id } })
                },
                permissions = info.permissions.joinToString(", "),
                hasWeb = info.hasWeb,
                workerOnline = kit.bridge.isConnected(info.id),
                error = info.error,
            )
        }
        adapter.submit(rows, if (rows.isEmpty()) "还没有插件——点「安装 .hpk」添加" else null)
    }

    // ---- 安装 ----

    private fun pickPackage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip"))
        }
        installLauncher.launch(intent)
    }

    private fun installFrom(uri: android.net.Uri) {
        val srv = server()
        if (srv == null) {
            toast("服务未运行，无法安装")
            return
        }
        // 安装要解压 + 校验，放后台线程；先复制到缓存目录（SAF 的 uri 不能直接当文件用）
        Thread {
            val tmp = java.io.File(cacheDir, "install-${System.currentTimeMillis()}.hpk")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("无法读取所选文件")
                val info = srv.hydroKit.install(tmp)
                runOnUiThread {
                    toast("已安装 ${info.name} v${info.version}")
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("安装失败: ${e.message}") }
            } finally {
                tmp.delete()
            }
        }.start()
    }

    // ---- 卸载 ----

    private fun confirmUninstall(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("卸载插件")
            .setMessage("确定卸载「$name」吗？\n插件的服务端存储会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("卸载") { _, _ ->
                val srv = server() ?: return@setPositiveButton
                Thread {
                    try {
                        srv.hydroKit.uninstall(id)
                        runOnUiThread { toast("已卸载 $name"); refresh() }
                    } catch (e: Exception) {
                        runOnUiThread { toast("卸载失败: ${e.message}") }
                    }
                }.start()
            }
            .show()
    }

    // ---- WebUI ----

    private fun openWebUi(id: String, name: String) {
        startActivity(PluginWebUiActivity.intent(this, id, name))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(context: Context): Intent = Intent(context, PluginsActivity::class.java)
    }
}
