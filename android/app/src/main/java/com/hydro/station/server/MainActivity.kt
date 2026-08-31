package com.hydro.station.server

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hydro.station.server.model.Command
import com.hydro.station.server.model.PairRequest
import com.hydro.station.server.model.TrustedDevice
import com.hydro.station.server.service.LockAccessibilityService
import com.hydro.station.server.service.ServerService
import com.hydro.station.server.ui.CommandAdapter
import com.hydro.station.server.ui.DeviceAdapter
import com.hydro.station.server.ui.PairRequestAdapter
import com.hydro.station.server.util.DataManager
import com.hydro.station.server.util.NetworkUtil

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dataManager by lazy { DataManager.get(this) }
    private val port = 9000

    // Views
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var tabStatus: ScrollView
    private lateinit var tabDevices: LinearLayout
    private lateinit var tabCommands: LinearLayout
    private lateinit var fabAddCommand: ExtendedFloatingActionButton

    // Status views
    private lateinit var tvServerStatus: TextView
    private lateinit var switchServer: MaterialSwitch
    private lateinit var tvIpAddress: TextView
    private lateinit var tvPort: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnEnableAccessibility: MaterialButton
    private lateinit var tvNotificationStatus: TextView
    private lateinit var btnEnableNotification: MaterialButton
    private lateinit var tvNoRequests: TextView
    private lateinit var rvPairRequests: RecyclerView

    // Interconnect status views
    private lateinit var tvInterconnectStatus: TextView
    private lateinit var tvInterconnectDetail: TextView

    // Devices views
    private lateinit var tvNoDevices: TextView
    private lateinit var rvDevices: RecyclerView

    // Commands views
    private lateinit var tvNoCommands: TextView
    private lateinit var rvCommands: RecyclerView

    // Guards the server switch against programmatic sync (refreshData)
    private var syncingServerSwitch = false

    // Adapters
    private val deviceAdapter = DeviceAdapter { device -> deleteDevice(device) }
    private val pairRequestAdapter = PairRequestAdapter(
        onAccept = { pair -> respondPair(pair, "accept") },
        onReject = { pair -> respondPair(pair, "reject") }
    )
    private val commandAdapter = CommandAdapter { index -> deleteCommand(index) }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshData()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupBottomNav()
        setupListeners()
        // Auto-start the server service by default when entering the app.
        if (ServerService.instance == null) {
            ServerService.start(this, port)
        }
        refreshData()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottomNav)
        tabStatus = findViewById(R.id.tabStatus)
        tabDevices = findViewById(R.id.tabDevices)
        tabCommands = findViewById(R.id.tabCommands)
        fabAddCommand = findViewById(R.id.fabAddCommand)

        tvServerStatus = findViewById(R.id.tvServerStatus)
        switchServer = findViewById(R.id.switchServer)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvPort = findViewById(R.id.tvPort)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        btnEnableNotification = findViewById(R.id.btnEnableNotification)
        tvNoRequests = findViewById(R.id.tvNoRequests)
        rvPairRequests = findViewById(R.id.rvPairRequests)

        tvInterconnectStatus = findViewById(R.id.tvInterconnectStatus)
        tvInterconnectDetail = findViewById(R.id.tvInterconnectDetail)

        tvNoDevices = findViewById(R.id.tvNoDevices)
        rvDevices = findViewById(R.id.rvDevices)

        tvNoCommands = findViewById(R.id.tvNoCommands)
        rvCommands = findViewById(R.id.rvCommands)

        // Setup RecyclerViews
        rvPairRequests.layoutManager = LinearLayoutManager(this)
        rvPairRequests.adapter = pairRequestAdapter
        rvPairRequests.isNestedScrollingEnabled = false

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        rvCommands.layoutManager = LinearLayoutManager(this)
        rvCommands.adapter = commandAdapter

        // Set port
        tvPort.text = "端口: $port"

        // Set IP
        val ip = NetworkUtil.getLocalIpAddress(this)
        tvIpAddress.text = "IP: $ip"
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_status -> {
                    showTab(tabStatus)
                    fabAddCommand.hide()
                    true
                }
                R.id.nav_devices -> {
                    showTab(tabDevices)
                    fabAddCommand.hide()
                    true
                }
                R.id.nav_commands -> {
                    showTab(tabCommands)
                    fabAddCommand.show()
                    true
                }
                else -> false
            }
        }
    }

    private fun showTab(view: View) {
        tabStatus.visibility = if (view == tabStatus) View.VISIBLE else View.GONE
        tabDevices.visibility = if (view == tabDevices) View.VISIBLE else View.GONE
        tabCommands.visibility = if (view == tabCommands) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        // Toolbar menu
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (syncingServerSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                ServerService.start(this, port)
                tvServerStatus.text = getString(R.string.server_running)
            } else {
                ServerService.stop(this)
                tvServerStatus.text = getString(R.string.server_stopped)
            }
        }

        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnEnableNotification.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        fabAddCommand.setOnClickListener {
            showAddCommandDialog()
        }
    }

    private fun refreshData() {
        // Server status
        val isRunning = ServerService.instance != null
        syncingServerSwitch = true
        switchServer.isChecked = isRunning
        syncingServerSwitch = false
        tvServerStatus.text = if (isRunning) getString(R.string.server_running) else getString(R.string.server_stopped)

        // Accessibility status
        val accEnabled = LockAccessibilityService.isRunning()
        if (accEnabled) {
            tvAccessibilityStatus.text = getString(R.string.accessibility_enabled)
            tvAccessibilityStatus.setTextColor(getColor(R.color.md_theme_success))
            btnEnableAccessibility.visibility = View.GONE
        } else {
            tvAccessibilityStatus.text = getString(R.string.accessibility_not_enabled)
            tvAccessibilityStatus.setTextColor(getColor(R.color.md_theme_error))
            btnEnableAccessibility.visibility = View.VISIBLE
        }

        // Notification listener status
        val notifEnabled = isNotificationServiceEnabled()
        if (notifEnabled) {
            tvNotificationStatus.text = getString(R.string.notification_enabled)
            tvNotificationStatus.setTextColor(getColor(R.color.md_theme_success))
            btnEnableNotification.visibility = View.GONE
        } else {
            tvNotificationStatus.text = getString(R.string.notification_not_enabled)
            tvNotificationStatus.setTextColor(getColor(R.color.md_theme_error))
            btnEnableNotification.visibility = View.VISIBLE
        }

        // Interconnect status
        updateInterconnectStatus()

        // Pair requests
        val pairs = dataManager.loadPendingPairs()
        if (pairs.isEmpty()) {
            tvNoRequests.visibility = View.VISIBLE
            rvPairRequests.visibility = View.GONE
        } else {
            tvNoRequests.visibility = View.GONE
            rvPairRequests.visibility = View.VISIBLE
            pairRequestAdapter.update(pairs)
        }

        // Devices
        val devices = dataManager.loadTrustedDevices()
        if (devices.isEmpty()) {
            tvNoDevices.visibility = View.VISIBLE
            rvDevices.visibility = View.GONE
        } else {
            tvNoDevices.visibility = View.GONE
            rvDevices.visibility = View.VISIBLE
            deviceAdapter.update(devices)
        }

        // Commands
        val commands = dataManager.loadCommands()
        if (commands.isEmpty()) {
            tvNoCommands.visibility = View.VISIBLE
            rvCommands.visibility = View.GONE
        } else {
            tvNoCommands.visibility = View.GONE
            rvCommands.visibility = View.VISIBLE
            commandAdapter.update(commands)
        }
    }

    private fun updateInterconnectStatus() {
        val handler = ServerService.instance?.getInterconnectHandler()
        if (handler == null) {
            tvInterconnectStatus.text = getString(R.string.interconnect_disconnected)
            tvInterconnectStatus.setTextColor(getColor(R.color.md_theme_outline))
            tvInterconnectDetail.text = "服务未启动"
            return
        }

        val status = handler.getStatus()
        if (!status.serviceConnected) {
            tvInterconnectStatus.text = getString(R.string.interconnect_connecting)
            tvInterconnectStatus.setTextColor(getColor(R.color.md_theme_warning))
            tvInterconnectDetail.text = "XMS 服务连接中..."
            return
        }

        if (status.watchNodes.isEmpty()) {
            tvInterconnectStatus.text = getString(R.string.interconnect_no_watch)
            tvInterconnectStatus.setTextColor(getColor(R.color.md_theme_outline))
            tvInterconnectDetail.text = "等待手表连接..."
            return
        }

        // Has connected watch(es)
        val node = status.watchNodes[0]
        tvInterconnectStatus.text = getString(R.string.interconnect_connected)
        tvInterconnectStatus.setTextColor(getColor(R.color.md_theme_success))

        val detailParts = mutableListOf<String>()
        detailParts.add(node.displayName)
        if (node.permissionGranted) {
            detailParts.add("已授权")
        } else {
            detailParts.add("等待授权...")
        }
        if (node.listenerRegistered) {
            detailParts.add("监听中")
        }
        tvInterconnectDetail.text = detailParts.joinToString(" · ")
    }

    private fun deleteDevice(device: TrustedDevice) {
        dataManager.removeTrustedDevice(device.deviceId)
        refreshData()
        Snackbar.make(rvDevices, "已删除设备", Snackbar.LENGTH_SHORT).show()
    }

    private fun respondPair(pair: PairRequest, action: String) {
        Thread {
            try {
                val url = "http://127.0.0.1:$port/api/pair/respond"
                val json = """{"device_id":"${pair.deviceId}","action":"$action","device_name":"${pair.deviceName}"}"""
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write(json.toByteArray())
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                dataManager.removePendingPair(pair.deviceId)
                if (action == "accept") {
                    dataManager.addTrustedDevice(pair.deviceId, pair.deviceName)
                } else {
                    dataManager.addRejected(pair.deviceId)
                }
            }
            handler.post { refreshData() }
        }.start()
    }

    private fun deleteCommand(index: Int) {
        dataManager.removeCommand(index)
        refreshData()
        Snackbar.make(rvCommands, "已删除命令", Snackbar.LENGTH_SHORT).show()
    }

    private fun showAddCommandDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_edit_command, null)
        val tilName = view.findViewById<TextInputLayout>(R.id.tilCommandName)
        val tilCommand = view.findViewById<TextInputLayout>(R.id.tilCommandContent)
        val etName = view.findViewById<TextInputEditText>(R.id.etCommandName)
        val etCommand = view.findViewById<TextInputEditText>(R.id.etCommandContent)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_command)
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""
                val command = etCommand.text?.toString()?.trim() ?: ""
                if (name.isEmpty() || command.isEmpty()) {
                    Snackbar.make(fabAddCommand, "名称和命令不能为空", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                dataManager.addCommand(name, command)
                refreshData()
                Snackbar.make(fabAddCommand, "已添加命令", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
        handler.postDelayed(refreshRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    /**
     * Check if the notification listener service is enabled in system settings.
     */
    private fun isNotificationServiceEnabled(): Boolean {
        val packageName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat != null) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == packageName) {
                    return true
                }
            }
        }
        return false
    }
}
