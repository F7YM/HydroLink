package com.hydro.link.server.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.hydro.link.server.R
import com.hydro.link.server.service.ServerService

/**
 * 插件 WebUI 页：用 WebView 打开插件包里的 `web/index.html`。
 *
 * 关键点：页面走 **同源** 地址
 * `http://127.0.0.1:<port>/api/hydro/webui/<pkg>/index.html`，
 * 所以插件页面可以直接调用 kit 能力端点（/api/hydro/worker 下的 store/notify 等，
 * 发通知等，按 manifest 权限校验），与桌面端的 WebUI 完全一致 —— 插件作者不需要
 * 为安卓写第二套页面。
 */
class PluginWebUiActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin_webui)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { pkg }

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = name
            setNavigationOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.webUiSubtitle).text = pkg

        webView = findViewById(R.id.webUiView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.setBackgroundColor(Color.parseColor("#121318"))
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 只允许留在本机服务端（插件页面是本地资源，不需要跳外链）
                return !request.url.toString().startsWith(serverBase())
            }
        }

        val base = serverBase()
        if (base.isEmpty()) {
            findViewById<TextView>(R.id.webUiSubtitle).text = "服务未运行，无法打开插件界面"
            return
        }
        webView.loadUrl("$base/api/hydro/webui/$pkg/index.html")
    }

    private fun serverBase(): String {
        val srv = ServerService.instance?.server() ?: return ""
        val port = srv.currentPort
        return if (port > 0) "http://127.0.0.1:$port" else ""
    }

    override fun onDestroy() {
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_NAME = "name"

        fun intent(context: Context, packageId: String, name: String): Intent =
            Intent(context, PluginWebUiActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageId)
                putExtra(EXTRA_NAME, name)
            }
    }
}

/** 插件列表的 RecyclerView 适配器（简单两行布局，不引额外依赖）。 */
class PluginAdapter(
    private val onOpenWebUi: (PluginRow) -> Unit,
    private val onUninstall: (PluginRow) -> Unit,
    private val onShowDetails: (PluginRow) -> Unit,
) : RecyclerView.Adapter<PluginAdapter.VH>() {

    private var rows: List<PluginRow> = emptyList()
    private var emptyText: String? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.pluginRow)
        val name: TextView = v.findViewById(R.id.pluginName)
        val meta: TextView = v.findViewById(R.id.pluginMeta)
        val open: View = v.findViewById(R.id.pluginOpen)
        val uninstall: View = v.findViewById(R.id.pluginUninstall)
    }

    fun submit(list: List<PluginRow>, empty: String?) {
        rows = list
        emptyText = empty
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_plugin, parent, false))

    override fun getItemCount(): Int = rows.size.coerceAtLeast(if (emptyText != null) 1 else 0)

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (rows.isEmpty()) {
            holder.name.text = emptyText
            holder.meta.visibility = View.GONE
            holder.open.visibility = View.GONE
            holder.uninstall.visibility = View.GONE
            holder.row.isClickable = false
            return
        }
        val r = rows[position]
        holder.name.text = r.name
        // 副标题只显示包名和版本；其余信息点卡片看弹窗
        holder.meta.text = r.pkgLine
        holder.meta.visibility = View.VISIBLE
        holder.open.visibility = if (r.hasWeb) View.VISIBLE else View.GONE
        holder.uninstall.visibility = View.VISIBLE
        holder.open.setOnClickListener { onOpenWebUi(r) }
        holder.uninstall.setOnClickListener { onUninstall(r) }
        holder.row.setOnClickListener { onShowDetails(r) }
    }
}
