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
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.RecyclerView
import com.hydro.link.server.R
import com.hydro.link.server.service.ServerService
import com.hydro.link.server.ui.material.ExpressiveBackButton
import com.hydro.link.server.ui.material.HydroMaterialTheme
import com.hydro.link.server.util.DataManager
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 插件 WebUI 页：用 WebView 打开插件包里的 `web/index.html`。
 *
 * 关键点：页面走 **同源** 地址
 * `http://127.0.0.1:<port>/api/hydro/webui/<pkg>/index.html`，
 * 所以插件页面可以直接调用 kit 能力端点（/api/hydro/worker 下的 store/notify 等，
 * 发通知等，按 manifest 权限校验），与桌面端的 WebUI 完全一致 —— 插件作者不需要
 * 为安卓写第二套页面。
 *
 * 顶栏跟随当前 UI 引擎（与权限管理 / 运行日志两个独立页同一套写法）：
 * miuix → MiuixTopAppBar；Google Material 3 → TopAppBar + ExpressiveBackButton。
 * 早先这里是 XML 的 MaterialToolbar，两个引擎下都不像，属于漏网之鱼。
 */
class PluginWebUiActivity : AppCompatActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { pkg }
        val miuix = DataManager.get(this).loadConfig().miuix
        SystemBars.apply(this, miuix)

        // 同源地址；服务没跑时只给一行提示，不加载任何 URL
        val base = serverBase()

        setContent {
            val content: @Composable () -> Unit = {
                WebUiBody(
                    name = name,
                    subtitle = if (base.isEmpty()) "服务未运行，无法打开插件界面" else pkg,
                    pkg = pkg,
                    base = base,
                    miuix = miuix,
                    onBack = { finish() },
                    onWebViewCreated = { webView = it },
                )
            }
            if (miuix) {
                MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) { content() }
            } else {
                HydroMaterialTheme { content() }
            }
        }
    }

    private fun serverBase(): String {
        val srv = ServerService.instance?.server() ?: return ""
        val port = srv.currentPort
        return if (port > 0) "http://127.0.0.1:$port" else ""
    }

    override fun onDestroy() {
        runCatching {
            webView?.stopLoading()
            webView?.destroy()
        }
        webView = null
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

/** 创建插件 WebView：只允许留在本机服务端（插件页面是本地资源，不需要跳外链）。 */
private fun createWebView(context: Context, pkg: String, base: String): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        setBackgroundColor(Color.parseColor("#121318"))
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !request.url.toString().startsWith(base)
            }
        }
        if (base.isNotEmpty()) loadUrl("$base/api/hydro/webui/$pkg/index.html")
    }

@Composable
private fun WebUiBody(
    name: String,
    subtitle: String,
    pkg: String,
    base: String,
    miuix: Boolean,
    onBack: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
) {
    if (miuix) {
        MiuixWebUiContent(name, subtitle, pkg, base, onBack, onWebViewCreated)
    } else {
        MdWebUiContent(name, subtitle, pkg, base, onBack, onWebViewCreated)
    }
}

@Composable
private fun MiuixWebUiContent(
    name: String,
    subtitle: String,
    pkg: String,
    base: String,
    onBack: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
) {
    MiuixScaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            MiuixTopAppBar(
                title = name,
                navigationIcon = {
                    MiuixIconButton(onClick = onBack) {
                        MiuixIcon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding()),
        ) {
            MiuixText(
                text = subtitle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            WebUiView(pkg, base, Modifier.weight(1f), onWebViewCreated)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdWebUiContent(
    name: String,
    subtitle: String,
    pkg: String,
    base: String,
    onBack: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = { ExpressiveBackButton(onClick = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding()),
        ) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            WebUiView(pkg, base, Modifier.weight(1f), onWebViewCreated)
        }
    }
}

@Composable
private fun WebUiView(pkg: String, base: String, modifier: Modifier, onWebViewCreated: (WebView) -> Unit) {
    AndroidView(
        factory = { ctx -> createWebView(ctx, pkg, base).also(onWebViewCreated) },
        modifier = modifier,
    )
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
