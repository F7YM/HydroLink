package com.hydro.station.server

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.hydro.station.server.util.DataManager
import com.hydro.station.server.util.ShizukuManager

class SettingsActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dataManager by lazy { DataManager.get(this) }
    private var switchListenerEnabled = true

    private lateinit var switchShizuku: MaterialSwitch
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvAppName: TextView

    private val statusRunnable = object : Runnable {
        override fun run() {
            updateShizukuStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        switchShizuku = findViewById(R.id.switchShizuku)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvAppName = findViewById(R.id.tvAppName)

        // Display version info
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        tvAppName.text = "HydroStation V${versionName}"

        // Initialize Shizuku listeners (lazy, safe)
        ShizukuManager.init()

        // Load config and set switch state without triggering listener
        val config = dataManager.loadConfig()
        switchListenerEnabled = false
        switchShizuku.isChecked = config.useShizuku
        switchListenerEnabled = true

        switchShizuku.setOnCheckedChangeListener { switch, isChecked ->
            if (!switchListenerEnabled) return@setOnCheckedChangeListener

            if (isChecked) {
                // Try to enable Shizuku
                if (!ShizukuManager.isShizukuRunning()) {
                    // Shizuku/Sui not running — revert switch
                    switchListenerEnabled = false
                    switch.isChecked = false
                    switchListenerEnabled = true
                    val cfg = dataManager.loadConfig()
                    cfg.useShizuku = false
                    dataManager.saveConfig(cfg)
                    Snackbar.make(switch, "Shizuku/Sui 未运行，请先启动", Snackbar.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }

                // Shizuku is running — request permission automatically
                val alreadyGranted = ShizukuManager.requestPermission { granted ->
                    handler.post {
                        if (granted) {
                            // Permission granted — save config
                            val cfg = dataManager.loadConfig()
                            cfg.useShizuku = true
                            dataManager.saveConfig(cfg)
                            Snackbar.make(switch, "Shizuku 已授权", Snackbar.LENGTH_SHORT).show()
                        } else {
                            // Permission denied — revert switch
                            switchListenerEnabled = false
                            switch.isChecked = false
                            switchListenerEnabled = true
                            val cfg = dataManager.loadConfig()
                            cfg.useShizuku = false
                            dataManager.saveConfig(cfg)
                            Snackbar.make(switch, "Shizuku 授权被拒绝", Snackbar.LENGTH_LONG).show()
                        }
                        updateShizukuStatus()
                    }
                }

                if (alreadyGranted) {
                    // Already had permission
                    val cfg = dataManager.loadConfig()
                    cfg.useShizuku = true
                    dataManager.saveConfig(cfg)
                    Snackbar.make(switch, "Shizuku 已就绪", Snackbar.LENGTH_SHORT).show()
                } else {
                    // Permission request dialog is showing — save config optimistically
                    // Will be reverted if denied
                    val cfg = dataManager.loadConfig()
                    cfg.useShizuku = true
                    dataManager.saveConfig(cfg)
                }
            } else {
                // Disable Shizuku
                val cfg = dataManager.loadConfig()
                cfg.useShizuku = false
                dataManager.saveConfig(cfg)
            }

            updateShizukuStatus()
        }
    }

    private fun updateShizukuStatus() {
        val running = ShizukuManager.isShizukuRunning()
        val hasPermission = ShizukuManager.hasPermission()

        tvShizukuStatus.text = when {
            !running -> "Shizuku/Sui 未运行"
            !hasPermission -> "Shizuku 运行中，未授权"
            else -> "Shizuku 已就绪"
        }

        // Auto-correct switch state if needed
        if (!running && switchShizuku.isChecked) {
            val cfg = dataManager.loadConfig()
            if (cfg.useShizuku) {
                cfg.useShizuku = false
                dataManager.saveConfig(cfg)
                switchListenerEnabled = false
                switchShizuku.isChecked = false
                switchListenerEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
        handler.postDelayed(statusRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuManager.destroy()
    }
}
