package com.hydro.link.server.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.hydro.link.server.R

/**
 * 插件管理页（设置里的入口）。
 *
 * 主界面底部的「插件」Tab 是同一个功能的常驻入口；两边都走 [PluginUi]，
 * 行为一致。这里保留独立页面是给"从设置进来"的用户用的。
 */
class PluginsActivity : AppCompatActivity() {

    private lateinit var adapter: PluginAdapter
    private val installLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        PluginUi.pickAndInstall(this, uri) { message -> toast(message); refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugins)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { finish() }
        }

        adapter = PluginAdapter(
            onOpenWebUi = { info -> PluginUi.openWebUi(this, info.id, info.name) },
            onUninstall = { info -> confirmUninstall(info.id, info.name) },
            onShowDetails = { info -> PluginUi.showDetails(this, info) },
        )
        findViewById<RecyclerView>(R.id.pluginList).apply {
            layoutManager = LinearLayoutManager(this@PluginsActivity)
            adapter = this@PluginsActivity.adapter
        }
        findViewById<View>(R.id.btnInstallPlugin).setOnClickListener {
            installLauncher.launch(PluginUi.pickerIntent())
        }
        findViewById<View>(R.id.btnRefreshPlugins).setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val srv = PluginUi.server()
        if (srv == null) {
            adapter.submit(emptyList(), "服务未运行——请先回到首页启动服务")
            return
        }
        val rows = PluginUi.rows(srv)
        adapter.submit(rows, null)
    }

    private fun confirmUninstall(id: String, name: String) {
        PluginUi.confirmUninstall(this, id, name) { message -> toast(message); refresh() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(context: Context): Intent = Intent(context, PluginsActivity::class.java)
    }
}
