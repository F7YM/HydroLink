package com.hydro.link.server

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hydro.link.server.util.DataManager
import com.hydro.link.server.util.RootShell

class SettingsActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dataManager by lazy { DataManager.get(this) }
    private var switchListenerEnabled = true

    private lateinit var switchRoot: MaterialSwitch
    private lateinit var tvRootStatus: TextView
    private lateinit var tvAppName: TextView

    private val statusRunnable = object : Runnable {
        override fun run() {
            updateRootStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // 插件管理入口（HydroKit）
        findViewById<android.view.View>(R.id.btnPlugins)?.setOnClickListener {
            startActivity(com.hydro.link.server.ui.PluginsActivity.intent(this))
        }

        switchRoot = findViewById(R.id.switchRoot)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvAppName = findViewById(R.id.tvAppName)

        // Display version info
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        tvAppName.text = "HydroLink ${versionName}"


        // Load config and set switch state without triggering listener
        val config = dataManager.loadConfig()
        switchListenerEnabled = false
        switchRoot.isChecked = config.useRoot
        switchListenerEnabled = true

        switchRoot.setOnCheckedChangeListener { switch, isChecked ->
            if (!switchListenerEnabled) return@setOnCheckedChangeListener

            if (isChecked) {
                // 打开前先警告：root 下命令以 uid 0 运行，插件请求的命令也走这条路
                MaterialAlertDialogBuilder(this)
                    .setTitle("使用 root 执行快速命令？")
                    .setMessage("使用 root 权限执行快速命令有安全风险，请自行检查命令。\n\n" +
                        "开启后 quick command 与插件请求的命令都会以 root 身份运行，请只安装可信插件。")
                    .setNegativeButton("取消") { _, _ ->
                        switchListenerEnabled = false
                        switch.isChecked = false
                        switchListenerEnabled = true
                    }
                    .setPositiveButton("仍然开启") { _, _ ->
                        Thread {
                            val ok = RootShell.isAvailable()
                            handler.post {
                                val cfg = dataManager.loadConfig()
                                cfg.useRoot = ok
                                dataManager.saveConfig(cfg)
                                if (!ok) {
                                    switchListenerEnabled = false
                                    switch.isChecked = false
                                    switchListenerEnabled = true
                                    Snackbar.make(switch, "root 不可用（未 root 或未授权）", Snackbar.LENGTH_LONG).show()
                                } else {
                                    Snackbar.make(switch, "已开启：命令将以 root 执行", Snackbar.LENGTH_SHORT).show()
                                }
                                updateRootStatus()
                            }
                        }.start()
                    }
                    .show()
            } else {
                val cfg = dataManager.loadConfig()
                cfg.useRoot = false
                dataManager.saveConfig(cfg)
                updateRootStatus()
            }
        }
    }

    private fun updateRootStatus() {
        // root 探测要起进程，放后台线程，避免每次刷新都卡 UI
        Thread {
            val ok = RootShell.isAvailable()
            handler.post {
                tvRootStatus.text = if (ok) "root 已就绪" else "root 不可用（未 root 或未授权）"
                val cfg = dataManager.loadConfig()
                if (!ok && cfg.useRoot) {
                    cfg.useRoot = false
                    dataManager.saveConfig(cfg)
                    switchListenerEnabled = false
                    switchRoot.isChecked = false
                    switchListenerEnabled = true
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        updateRootStatus()
        handler.postDelayed(statusRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
