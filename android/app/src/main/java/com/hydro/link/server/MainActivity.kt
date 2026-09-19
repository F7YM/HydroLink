package com.hydro.link.server

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
import com.hydro.link.server.model.Command
import com.hydro.link.server.model.PairRequest
import com.hydro.link.server.model.TrustedDevice
import com.hydro.link.server.service.LockAccessibilityService
import com.hydro.link.server.service.ServerService
import com.hydro.link.server.ui.CommandAdapter
import com.hydro.link.server.ui.PluginAdapter
import com.hydro.link.server.ui.PluginUi
import com.hydro.link.server.ui.DeviceAdapter
import com.hydro.link.server.ui.PairRequestAdapter
import com.hydro.link.server.util.DataManager
import com.hydro.link.server.util.NetworkUtil

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
    private lateinit var tvPostNotifStatus: TextView
    private lateinit var btnPostNotif: MaterialButton
    private lateinit var cardAccessibility: View
    private lateinit var cardNotifListener: View
    private lateinit var cardNotifPermission: View
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

    // Plugins views（HydroKit 插件 Tab）
    private lateinit var tabPlugins: LinearLayout
    private lateinit var tvPluginSummary: TextView
    private lateinit var rvPlugins: RecyclerView

    // Guards the server switch against programmatic sync (refreshData)
    private var syncingServerSwitch = false

    // Adapters
    private val deviceAdapter = DeviceAdapter { device -> deleteDevice(device) }
    private val pairRequestAdapter = PairRequestAdapter(
        onAccept = { pair -> respondPair(pair, "accept") },
        onReject = { pair -> respondPair(pair, "reject") }
    )
    private val pluginAdapter = PluginAdapter(
        onOpenWebUi = { row -> PluginUi.openWebUi(this, row.id, row.name) },
        onUninstall = { row -> confirmUninstallPlugin(row.id, row.name) },
        onShowDetails = { row -> PluginUi.showDetails(this, row) },
    )

    /** 插件 Tab 的「安装 .hpk」（SAF 选择器）。 */
    private val pluginInstallLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        // 安装前会先弹确认框（清单摘要 + 权限 + 安全提醒），确认后才真正安装
        PluginUi.pickAndInstall(this, uri) { message ->
            Snackbar.make(tabPlugins, message, Snackbar.LENGTH_SHORT).show()
            refreshPlugins()
        }
    }

    private val commandAdapter = CommandAdapter(
        onEdit = { index -> showAddCommandDialog(index) },
        onDelete = { index -> deleteCommand(index) },
    )

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshData()
            handler.postDelayed(this, 2000)
        }
    }

    /**
     * Android 13+ 发通知要 POST_NOTIFICATIONS 运行时授权。没授权时
     * NotificationManager.notify() 不报错但通知不出现——插件侧会以为成功，
     * 是典型的"只在真机上才发现的静默失败"，所以进 App 就主动要一次。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Snackbar.make(
                    findViewById(R.id.tabStatus),
                    "未授予通知权限，插件通知将不可用（可在系统设置里开启）",
                    Snackbar.LENGTH_LONG,
                ).show()
            }
            refreshData()
        }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
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
        requestNotificationPermissionIfNeeded()
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
        tvPostNotifStatus = findViewById(R.id.tvPostNotifStatus)
        btnPostNotif = findViewById(R.id.btnPostNotif)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardNotifListener = findViewById(R.id.cardNotifListener)
        cardNotifPermission = findViewById(R.id.cardNotifPermission)
        tvNoRequests = findViewById(R.id.tvNoRequests)
        rvPairRequests = findViewById(R.id.rvPairRequests)

        tvInterconnectStatus = findViewById(R.id.tvInterconnectStatus)
        tvInterconnectDetail = findViewById(R.id.tvInterconnectDetail)

        tvNoDevices = findViewById(R.id.tvNoDevices)
        rvDevices = findViewById(R.id.rvDevices)

        tvNoCommands = findViewById(R.id.tvNoCommands)
        rvCommands = findViewById(R.id.rvCommands)
        tabPlugins = findViewById(R.id.tabPlugins)
        tvPluginSummary = findViewById(R.id.tvPluginSummary)
        rvPlugins = findViewById(R.id.rvPlugins)

        // Setup RecyclerViews
        rvPairRequests.layoutManager = LinearLayoutManager(this)
        rvPairRequests.adapter = pairRequestAdapter
        rvPairRequests.isNestedScrollingEnabled = false

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        rvCommands.layoutManager = LinearLayoutManager(this)
        rvCommands.adapter = commandAdapter

        rvPlugins.layoutManager = LinearLayoutManager(this)
        rvPlugins.adapter = pluginAdapter

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
                R.id.nav_plugins -> {
                    showTab(tabPlugins)
                    fabAddCommand.hide()
                    refreshPlugins()
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
        tabPlugins.visibility = if (view == tabPlugins) View.VISIBLE else View.GONE
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

        // POST_NOTIFICATIONS：Android 13+ 被拒后系统不再弹窗，只能去设置里开
        btnPostNotif.setOnClickListener {
            val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            }
            startActivity(intent)
        }

        fabAddCommand.setOnClickListener {
            showAddCommandDialog(null)
        }

        findViewById<View>(R.id.btnInstallPluginTab).setOnClickListener {
            pluginInstallLauncher.launch(PluginUi.pickerIntent())
        }
        findViewById<View>(R.id.btnRefreshPluginsTab).setOnClickListener { refreshPlugins() }
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
        // 已授权的权限卡片直接隐藏（用户要求：状态页只留"还需要处理的事"）
        cardAccessibility.visibility = if (accEnabled) View.GONE else View.VISIBLE
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
        cardNotifListener.visibility = if (notifEnabled) View.GONE else View.VISIBLE
        if (notifEnabled) {
            tvNotificationStatus.text = getString(R.string.notification_enabled)
            tvNotificationStatus.setTextColor(getColor(R.color.md_theme_success))
            btnEnableNotification.visibility = View.GONE
        } else {
            tvNotificationStatus.text = getString(R.string.notification_not_enabled)
            tvNotificationStatus.setTextColor(getColor(R.color.md_theme_error))
            btnEnableNotification.visibility = View.VISIBLE
        }

        // 通知权限（POST_NOTIFICATIONS）：插件 notify 能力的前置条件
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val postNotifOk = nm.areNotificationsEnabled()
        cardNotifPermission.visibility = if (postNotifOk) View.GONE else View.VISIBLE
        if (postNotifOk) {
            tvPostNotifStatus.text = "已授权，插件可以发通知"
            tvPostNotifStatus.setTextColor(getColor(R.color.md_theme_success))
            btnPostNotif.visibility = View.GONE
        } else {
            tvPostNotifStatus.text = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                "未授权：Android 13+ 需授予「通知」权限，否则插件通知会被系统丢弃"
            } else {
                "未开启：系统里本应用的通知被关闭，插件通知会被丢弃"
            }
            tvPostNotifStatus.setTextColor(getColor(R.color.md_theme_error))
            btnPostNotif.visibility = View.VISIBLE
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

        // 插件 Tab（只在可见时刷新：要读插件目录，每 2 秒一次没必要）
        if (::tabPlugins.isInitialized && tabPlugins.visibility == View.VISIBLE) refreshPlugins()

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

        // 已连接就只显示设备名（不再堆"已授权/监听中"这类实现细节）
        tvInterconnectDetail.text = node.displayName
        if (!node.permissionGranted) {
            tvInterconnectDetail.text = "${node.displayName} · 等待授权..."
        }
    }

    /** 刷新插件列表与摘要（worker 在线状态来自 bridge，能直接看出插件跑没跑起来）。 */
    private fun refreshPlugins() {
        val srv = PluginUi.server()
        if (srv == null) {
            pluginAdapter.submit(emptyList(), "服务未运行——上面的开关打开后再试")
            tvPluginSummary.text = "服务未运行"
            return
        }
        val rows = PluginUi.rows(srv)
        pluginAdapter.submit(rows, if (rows.isEmpty()) "还没有插件——点「安装 .hpk」添加" else null)
        tvPluginSummary.text = if (rows.isEmpty()) "已安装 0 个插件" else "已安装 ${rows.size} 个插件"
    }

    private fun confirmUninstallPlugin(id: String, name: String) {
        PluginUi.confirmUninstall(this, id, name) { message ->
            Snackbar.make(tabPlugins, message, Snackbar.LENGTH_SHORT).show()
            refreshPlugins()
        }
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

    /**
     * 新建 / 编辑快速命令（同一个弹窗：index == null 表示新建）。
     * 编辑时预填现有名称与命令。
     */
    private fun showAddCommandDialog(index: Int? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_command, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etCommandName)
        val etCommand = view.findViewById<TextInputEditText>(R.id.etCommandContent)
        val editing = index != null && index in dataManager.loadCommands().indices
        val existing = if (editing) dataManager.loadCommands()[index!!] else null
        if (existing != null) {
            etName.setText(existing.name)
            etCommand.setText(existing.command)
            etCommand.setSelection(existing.command.length)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (editing) "编辑命令" else getString(R.string.add_command))
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""
                val command = etCommand.text?.toString()?.trim() ?: ""
                if (name.isEmpty() || command.isEmpty()) {
                    Snackbar.make(fabAddCommand, "名称和命令不能为空", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (editing) {
                    dataManager.updateCommand(index!!, name, command)
                    Snackbar.make(fabAddCommand, "已保存", Snackbar.LENGTH_SHORT).show()
                } else {
                    dataManager.addCommand(name, command)
                    Snackbar.make(fabAddCommand, "已添加命令", Snackbar.LENGTH_SHORT).show()
                }
                refreshData()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
        // 每 2 秒刷新一次状态（服务/互联/worker 在线数都要是活的）
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
