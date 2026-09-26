package com.hydro.link.server

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hydro.link.server.model.Command
import com.hydro.link.server.model.PairRequest
import com.hydro.link.server.model.TrustedDevice
import com.hydro.link.server.service.LockAccessibilityService
import com.hydro.link.server.service.ServerService
import com.hydro.link.server.ui.CommandAdapter
import com.hydro.link.server.ui.GlassTabHost
import com.hydro.link.server.ui.PluginAdapter
import com.hydro.link.server.ui.PluginUi
import com.hydro.link.server.ui.DeviceAdapter
import com.hydro.link.server.ui.PairRequestAdapter
import com.hydro.link.server.ui.miuix.MiuixActions
import com.hydro.link.server.ui.miuix.MiuixModel
import com.hydro.link.server.ui.miuix.Tone
import com.hydro.link.server.util.DataManager
import com.hydro.link.server.util.NetworkUtil
import com.hydro.link.server.util.RootShell

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dataManager by lazy { DataManager.get(this) }
    private val port = 9000

    // Views
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var tabStatus: ScrollView
    private lateinit var tabDevices: LinearLayout
    private lateinit var tabCommands: LinearLayout
    private lateinit var fabAddCommand: ExtendedFloatingActionButton

    // Status views
    private lateinit var tvServerStatus: TextView
    private lateinit var cardServer: com.google.android.material.card.MaterialCardView
    private lateinit var ivServerStatusIcon: android.widget.ImageView
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

    // Settings views（原 SettingsActivity 并入「设置」Tab）
    private lateinit var tabSettings: ScrollView
    private lateinit var tvAppName: TextView
    private lateinit var switchRoot: MaterialSwitch
    private lateinit var tvRootStatus: TextView
    private lateinit var switchBoot: MaterialSwitch
    private lateinit var switchLiquidGlass: MaterialSwitch
    private lateinit var switchMiuix: MaterialSwitch
    private var settingsSwitchSyncing = false

    // 玻璃 / miuix 底栏（compose 承载，见 GlassTabHost）：页面进组合，状态归这里管
    private lateinit var composeHost: ComposeView
    private lateinit var contentFrame: FrameLayout
    private var page: View? = null               // 解出的 activity_main_body（进 AndroidView 的页面主体）
    private var fabBaseMarginBottom = 0
    private val glassOn = mutableStateOf(false)
    private val miuixOn = mutableStateOf(false)  // miuix 底栏：复用 Liquid Glass 胶囊，只管配色
    private val navHeightPx = mutableStateOf(0)
    private val selectedIndex = mutableStateOf(0) // 当前 Tab 下标：玻璃胶囊与真实底栏共用

    // Guards the server switch against programmatic sync (refreshData)
    /** 本次会话已发出服务启动请求（乐观显示运行中，等 instance 落地后再以实况为准）。 */
    private var serverStartRequested = false

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
            notifyUser(tabPlugins, message)
            refreshPlugins()
        }
    }

    private val commandAdapter = CommandAdapter(
        onEdit = { index -> showAddCommandDialog(index) },
        onDelete = { index -> deleteCommand(index) },
    )

    // miuix 模式：状态由 refreshData 等发布，动作全部桥到上面的现有实现
    //（XML 开关走 performClick 复用原监听器——root 确认弹窗/落盘一行不重复）
    private val miuixModel = MiuixModel()
    private val miuixActions = MiuixActions(
        onRefresh = { refreshData() },
        onOpenPermissions = { startActivity(Intent(this, com.hydro.link.server.ui.PermissionsActivity::class.java)) },
        onOpenLogs = { startActivity(Intent(this, com.hydro.link.server.ui.LogsActivity::class.java)) },
        onEnableAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        onEnableNotificationListener = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        onOpenNotificationSettings = { openNotificationSettings() },
        onAcceptPair = { respondPair(it, "accept") },
        onRejectPair = { respondPair(it, "reject") },
        onDeleteDevice = { deleteDevice(it) },
        onAddCommand = { miuixModel.cmdDialog = com.hydro.link.server.ui.miuix.CmdEdit(null) },
        onEditCommand = { i ->
            val list = dataManager.loadCommands()
            if (i in list.indices) {
                miuixModel.cmdDialog = com.hydro.link.server.ui.miuix.CmdEdit(i, list[i].name, list[i].command)
            }
        },
        onDeleteCommand = { deleteCommand(it) },
        onSaveCommand = { index, name, command -> saveCommand(index, name, command) },
        onRefreshPlugins = { refreshPlugins() },
        onInstallPlugin = { pluginInstallLauncher.launch(PluginUi.pickerIntent()) },
        onPluginOpenWebUi = { PluginUi.openWebUi(this, it.id, it.name) },
        onPluginUninstall = { confirmUninstallPlugin(it.id, it.name) },
        onPluginDetails = { PluginUi.showDetails(this, it) },
        onToggleRoot = { forceSwitch(switchRoot, it); refreshData() },
        onToggleBoot = { forceSwitch(switchBoot, it); refreshData() },
        onToggleGlass = { forceSwitch(switchLiquidGlass, it); refreshData() },
        onToggleMiuix = { forceSwitch(switchMiuix, it); refreshData() },
        onRootConfirmDismiss = { miuixModel.rootConfirm = false },
        onRootConfirmAccept = {
            miuixModel.rootConfirm = false
            enableRootWithProbe()
        },
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
                notifyUser(
                    findViewById(R.id.tabStatus),
                    "未授予通知权限，插件通知将不可用（可在系统设置里开启）",
                    long = true,
                )
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
        // 页面主体先解出来（不挂树）：内容要进 Compose 的 AndroidView，壳
        // （activity_main）里只有 composeHost + 浮层 bottomNav —— Liquid Glass 的
        // layerBackdrop 要求内容与玻璃条在同一个组合里
        val body = layoutInflater.inflate(R.layout.activity_main_body, null)
        body.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        page = body
        setContentView(R.layout.activity_main)

        initViews()
        page?.let { attachScrollEndHaptic(it) }
        setupBottomNav()
        setupListeners()
        setupGlassHost()
        // Auto-start the server service by default when entering the app.
        if (ServerService.instance == null) {
            ServerService.start(this, port)
            serverStartRequested = true
        }
        requestNotificationPermissionIfNeeded()
        applyStatusBarMode()
        refreshData()
    }

    /**
     * 页面主体挂在 Compose 的 AndroidView 里，decor 视树下未必可达——先查 page；
     * 壳里的 bottomNav / composeHost 由 super 找到。
     */
    override fun <T : View> findViewById(id: Int): T {
        val inPage = page?.findViewById<T>(id)
        if (inPage != null) return inPage
        return super.findViewById(id)
    }

    private fun initViews() {
        bottomNav = findViewById(R.id.bottomNav)
        composeHost = findViewById(R.id.composeHost)
        contentFrame = findViewById(R.id.contentFrame)
        tabStatus = findViewById(R.id.tabStatus)
        tabDevices = findViewById(R.id.tabDevices)
        tabCommands = findViewById(R.id.tabCommands)
        fabAddCommand = findViewById(R.id.fabAddCommand)
        fabBaseMarginBottom = (fabAddCommand.layoutParams as FrameLayout.LayoutParams).bottomMargin

        tvServerStatus = findViewById(R.id.tvServerStatus)
        cardServer = findViewById(R.id.cardServer)
        ivServerStatusIcon = findViewById(R.id.ivServerStatusIcon)
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

        // 设置 Tab：视图与初值（监听器到 setupListeners 才挂，这里赋值不会触发）
        tabSettings = findViewById(R.id.tabSettings)
        tvAppName = findViewById(R.id.tvAppName)
        switchRoot = findViewById(R.id.switchRoot)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        switchBoot = findViewById(R.id.switchBoot)
        switchLiquidGlass = findViewById(R.id.switchLiquidGlass)
        switchMiuix = findViewById(R.id.switchMiuix)

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        tvAppName.text = "HydroLink $versionName"
        miuixModel.appName = tvAppName.text.toString()

        val cfg = dataManager.loadConfig()
        switchRoot.isChecked = cfg.useRoot
        switchBoot.isChecked = cfg.bootStart
        switchLiquidGlass.isChecked = cfg.liquidGlass
        glassOn.value = cfg.liquidGlass
        switchMiuix.isChecked = cfg.miuix
        miuixOn.value = cfg.miuix
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            val handled = when (item.itemId) {
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
                R.id.nav_settings -> {
                    showTab(tabSettings)
                    fabAddCommand.hide()
                    updateRootStatus() // 首次进入先探一次 root
                    true
                }
                else -> false
            }
            if (handled) selectedIndex.value = tabIndexOf(item.itemId)
            handled
        }
    }

    private fun tabIndexOf(itemId: Int): Int =
        (0 until bottomNav.menu.size()).firstOrNull { bottomNav.menu.getItem(it).itemId == itemId } ?: 0

    private fun showTab(view: View) {
        // 页面切换加个淡入淡出（与 miuix 侧 AnimatedContent 呼应），别硬切
        androidx.transition.TransitionManager.beginDelayedTransition(
            contentFrame,
            androidx.transition.Fade().setDuration(180),
        )
        tabStatus.visibility = if (view == tabStatus) View.VISIBLE else View.GONE
        tabDevices.visibility = if (view == tabDevices) View.VISIBLE else View.GONE
        tabCommands.visibility = if (view == tabCommands) View.VISIBLE else View.GONE
        tabPlugins.visibility = if (view == tabPlugins) View.VISIBLE else View.GONE
        tabSettings.visibility = if (view == tabSettings) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnEnableNotification.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        // POST_NOTIFICATIONS：Android 13+ 被拒后系统不再弹窗，只能去设置里开
        btnPostNotif.setOnClickListener { openNotificationSettings() }

        fabAddCommand.setOnClickListener {
            showAddCommandDialog(null)
        }

        findViewById<View>(R.id.btnInstallPluginTab).setOnClickListener {
            pluginInstallLauncher.launch(PluginUi.pickerIntent())
        }
        findViewById<View>(R.id.btnRefreshPluginsTab).setOnClickListener { refreshPlugins() }

        // 设置 Tab：root 执行（开启前警告；root 不可用时由 updateRootStatus 拨回）
        switchRoot.setOnCheckedChangeListener { switch, isChecked ->
            if (settingsSwitchSyncing) return@setOnCheckedChangeListener
            if (isChecked) {
                if (miuixOn.value) {
                    // miuix 模式：确认框换成 WindowDialog（状态在模型里，MiuixApp 渲染）
                    miuixModel.rootConfirm = true
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("使用 root 执行快速命令？")
                        .setMessage(
                            "使用 root 权限执行快速命令有安全风险，请自行检查命令。\n\n" +
                                "开启后 quick command 与插件请求的命令都会以 root 身份运行，请只安装可信插件。"
                        )
                        .setNegativeButton("取消") { _, _ ->
                            settingsSwitchSyncing = true
                            switch.isChecked = false
                            settingsSwitchSyncing = false
                        }
                        .setPositiveButton("仍然开启") { _, _ -> enableRootWithProbe() }
                        .show()
                }
            } else {
                val cfg = dataManager.loadConfig()
                cfg.useRoot = false
                dataManager.saveConfig(cfg)
                updateRootStatus()
            }
        }

        // 设置 Tab：开机自启（默认关；打开后重启由 BootReceiver 拉起服务）
        switchBoot.setOnCheckedChangeListener { switch, isChecked ->
            if (settingsSwitchSyncing) return@setOnCheckedChangeListener
            val cfg = dataManager.loadConfig()
            cfg.bootStart = isChecked
            dataManager.saveConfig(cfg)
            notifyUser(
                switch,
                if (isChecked) "已开启：重启后自动启动服务" else "已关闭：重启后需手动打开 App",
            )
        }

        // 设置 Tab：Liquid Glass 效果（默认关；FAQ 有 RenderThread 崩溃案例，必须可关）
        switchLiquidGlass.setOnCheckedChangeListener { _, isChecked ->
            if (settingsSwitchSyncing) return@setOnCheckedChangeListener
            val cfg = dataManager.loadConfig()
            cfg.liquidGlass = isChecked
            dataManager.saveConfig(cfg)
            glassOn.value = isChecked
            applyBottomBarMode()
        }

        // 设置 Tab：miuix 界面风格（miuix 开=miuix 底栏，Liquid Glass 同开才出玻璃胶囊；不弹提示）
        switchMiuix.setOnCheckedChangeListener { _, isChecked ->
            if (settingsSwitchSyncing) return@setOnCheckedChangeListener
            val cfg = dataManager.loadConfig()
            cfg.miuix = isChecked
            dataManager.saveConfig(cfg)
            miuixOn.value = isChecked
            applyBottomBarMode()
        }

    }

    /**
     * Liquid Glass 承载层：页面进 Compose 组合；底栏量到真实高度后喂给玻璃条，
     * 并按配置应用初始状态（背景/内边距/FAB）。
     */
    private fun setupGlassHost() {
        val menu = bottomNav.menu
        val icons = (0 until menu.size()).map { menu.getItem(it).icon!! }
        val labels = (0 until menu.size()).map { menu.getItem(it).title!! }
        composeHost.setContent {
            // NavHost / miuix WindowDialog 都要 NavigationEventDispatcherOwner，而 Extendable
            // 的 ComposeView 不保证拿到 ViewTree 上的那个；直接把 Activity（activity 1.13
            // 起 ComponentActivity 就是该 Owner）提供进组合，返回键/转场才都可用。
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner.provides(this)
            ) {
                GlassTabHost(
                    glassOn = glassOn.value,
                    miuixOn = miuixOn.value,
                    page = page ?: return@CompositionLocalProvider,
                    model = miuixModel,
                    actions = miuixActions,
                    selectedIndex = { selectedIndex.value },
                    onTabSelected = { index -> bottomNav.selectedItemId = menu.getItem(index).itemId },
                    tabIcons = icons,
                    tabLabels = labels,
                )
            }
        }
        bottomNav.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h > 0 && h != navHeightPx.value) {
                navHeightPx.value = h
                applyBottomBarMode()
            }
        }
    }

    /**
     * 底栏模式：玻璃或 miuix 任一开——真实底栏藏起来（玻璃开=胶囊接管、miuix 开=
     * miuix 底栏接管）。内容**不垫 contentFrame 底部**：要让内容流到 Liquid Glass
     * 底下参与折射；所谓安全区改成「页面下固定加一段空白」——给各页滚动容器末尾
     * 垫上底栏高度（`clipToPadding=false`，滚动时内容仍穿过玻璃下方）。FAB 抬到
     * 胶囊上方。全关时才用 contentFrame 垫底给真实底栏让位。
     */
    private fun applyBottomBarMode() {
        val composeBar = glassOn.value || miuixOn.value
        // 冷启动即开玻璃时底栏 GONE 未量过高度，按底栏标准 80dp 兜底
        val navHeight = if (navHeightPx.value > 0) navHeightPx.value
        else (80 * resources.displayMetrics.density).toInt()
        val fabParams = fabAddCommand.layoutParams as FrameLayout.LayoutParams
        bottomNav.visibility = if (composeBar) View.GONE else View.VISIBLE
        if (composeBar) {
            contentFrame.setPadding(0, 0, 0, 0)
            fabParams.bottomMargin = fabBaseMarginBottom + navHeight
            pageScrollers().forEach { v ->
                (v as? ViewGroup)?.apply {
                    clipToPadding = false
                    setPadding(0, 0, 0, navHeight)
                }
            }
        } else {
            contentFrame.setPadding(0, 0, 0, navHeight)
            fabParams.bottomMargin = fabBaseMarginBottom
            pageScrollers().forEach { v ->
                (v as? ViewGroup)?.apply {
                    clipToPadding = false
                    setPadding(0, 0, 0, 0)
                }
            }
        }
        fabAddCommand.layoutParams = fabParams
        applyStatusBarMode()
    }

    /**
     * 系统状态栏/导航栏配色跟随界面模式：miuix 下与页面底色一致（暗色纯黑、亮色
     * surface #F7F7F7），否则深色页面顶着一条灰状态栏很出戏；MD3 用应用背景色。
     * 图标明暗同时切换（暗色页面用浅色图标）。非 edge-to-edge，靠 window 颜色对齐。
     */
    private fun applyStatusBarMode() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val color = when {
            miuixOn.value && !night -> 0xFFF7F7F7.toInt()
            miuixOn.value -> 0xFF000000.toInt()
            else -> ContextCompat.getColor(this, R.color.md_theme_background)
        }
        window.statusBarColor = color
        window.navigationBarColor = color
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    /** MD3 页面里实际滚动的容器：安全区的「页面下固定空白」垫在它们尾部。 */
    private fun pageScrollers(): List<View> =
        listOfNotNull(tabStatus, rvDevices, rvCommands, rvPlugins, tabSettings)

    /**
     * MD3（XML）列表滑到顶/底时补一次系统触觉反馈——MIUI 开了振动反馈就会在这时震
     * 一下（InstallerX 的效果即来自 miuix 的 scrollEndHaptic；miuix 模式的列表已接，
     * 这里补上 XML 页面，两边一致）。到达边界的状态变化才触发，避免停在边上连震。
     */
    private fun attachScrollEndHaptic(root: View) {
        val scrollers = ArrayList<View>()
        fun walk(v: View) {
            if (v is RecyclerView || v is ScrollView) scrollers.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        scrollers.forEach { v ->
            var wasTop = !v.canScrollVertically(-1)
            var wasBottom = !v.canScrollVertically(1)
            fun check() {
                val top = !v.canScrollVertically(-1)
                val bottom = !v.canScrollVertically(1)
                if (top != wasTop && top) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                if (bottom != wasBottom && bottom) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                wasTop = top
                wasBottom = bottom
            }
            when (v) {
                is RecyclerView -> v.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (dy != 0) check()
                    }
                })
                is ScrollView -> v.setOnScrollChangeListener { _, _, _, _, _ -> check() }
            }
        }
    }

    /** miuix 动作桥：把目标值拨到 XML 开关上，走原监听器（确认弹窗/落盘/后台探测复用）。 */
    private fun forceSwitch(sw: MaterialSwitch, want: Boolean) {
        if (sw.isChecked != want) sw.performClick()
    }

    /**
     * 统一提示：一律走系统 Toast——Snackbar 会被底部 Tab 栏挡住，
     * miuix 与 MD3 两种界面下行为一致。
     */
    private fun notifyUser(anchor: View, message: String, long: Boolean = false) {
        android.widget.Toast.makeText(
            anchor.context,
            message,
            if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /** POST_NOTIFICATIONS 被拒后系统不再弹窗，跳系统设置里开（状态页按钮与 miuix 界面共用）。 */
    private fun openNotificationSettings() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", packageName, null))
        }
        startActivity(intent)
    }

    private fun refreshData() {
        // Server status
        // 启动请求发出即视为运行中：服务 onCreate 由 AMS 异步落地，紧跟其后查
        // instance 会短暂为 null，首帧先闪「未运行」再翻回去（用户反馈的"顿一下"）
        val isRunning = ServerService.instance != null || serverStartRequested
        if (ServerService.instance != null) serverStartRequested = false
        tvServerStatus.text = if (isRunning) getString(R.string.server_running) else getString(R.string.server_stopped)
        // 状态卡随运行状态着色（InstallerX 同款：运行绿容器 / 停止红容器）+ 大图标水印
        cardServer.setCardBackgroundColor(
            MaterialColors.getColor(
                cardServer,
                if (isRunning) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorErrorContainer,
            )
        )
        ivServerStatusIcon.setImageResource(
            if (isRunning) R.drawable.ic_check_circle_outline else R.drawable.ic_error_outline
        )
        ivServerStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (isRunning) R.color.md_theme_success else R.color.md_theme_error,
            )
        )

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

        // 设置 Tab（root 探测起进程，只在可见时刷）
        if (::tabSettings.isInitialized && tabSettings.visibility == View.VISIBLE) updateRootStatus()

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

        // 发布给 miuix 界面（与上面完全同一数据源；MD3 模式没有组合订阅，写入零成本）
        val m = miuixModel
        m.serverRunning = isRunning
        m.ipText = tvIpAddress.text.toString()
        m.portText = tvPort.text.toString()
        m.accVisible = cardAccessibility.visibility == View.VISIBLE
        m.accText = tvAccessibilityStatus.text.toString()
        m.notifListenerVisible = cardNotifListener.visibility == View.VISIBLE
        m.notifListenerText = tvNotificationStatus.text.toString()
        m.notifPermVisible = cardNotifPermission.visibility == View.VISIBLE
        m.notifPermText = tvPostNotifStatus.text.toString()
        m.pairs = pairs
        m.devices = devices
        m.commands = commands
        m.serverVersion = BuildConfig.VERSION_NAME
        val cfg = dataManager.loadConfig()
        m.useRoot = cfg.useRoot
        m.bootStart = cfg.bootStart
        m.liquidGlass = cfg.liquidGlass
        m.miuix = cfg.miuix
    }

    private fun updateInterconnectStatus() {
        val m = miuixModel
        val handler = ServerService.instance?.getInterconnectHandler()
        if (handler == null) {
            tvInterconnectStatus.text = getString(R.string.interconnect_disconnected)
            tvInterconnectStatus.setTextColor(getColor(R.color.md_theme_outline))
            tvInterconnectDetail.text = "服务未启动"
            m.interconnectText = getString(R.string.interconnect_disconnected)
            m.interconnectTone = Tone.Outline
            m.interconnectDetail = "服务未启动"
            return
        }

        val status = handler.getStatus()
        if (!status.serviceConnected) {
            tvInterconnectStatus.text = getString(R.string.interconnect_connecting)
            tvInterconnectStatus.setTextColor(getColor(R.color.md_theme_warning))
            tvInterconnectDetail.text = "XMS 服务连接中..."
            m.interconnectText = getString(R.string.interconnect_connecting)
            m.interconnectTone = Tone.Warning
            m.interconnectDetail = "XMS 服务连接中..."
            return
        }

        if (status.watchNodes.isEmpty()) {
            tvInterconnectStatus.text = getString(R.string.interconnect_no_watch)
            tvInterconnectStatus.setTextColor(getColor(R.color.md_theme_outline))
            tvInterconnectDetail.text = "等待手表连接..."
            m.interconnectText = getString(R.string.interconnect_no_watch)
            m.interconnectTone = Tone.Outline
            m.interconnectDetail = "等待手表连接..."
            return
        }

        // Has connected watch(es)
        val node = status.watchNodes[0]
        tvInterconnectStatus.text = getString(R.string.interconnect_connected)
        tvInterconnectStatus.setTextColor(getColor(R.color.md_theme_success))
        m.interconnectText = getString(R.string.interconnect_connected)
        m.interconnectTone = Tone.Success

        // 已连接就只显示设备名（不再堆"已授权/监听中"这类实现细节）
        tvInterconnectDetail.text = node.displayName
        if (!node.permissionGranted) {
            tvInterconnectDetail.text = "${node.displayName} · 等待授权..."
        }
        m.interconnectDetail = tvInterconnectDetail.text.toString()
    }

    /**
     * root 探测要起进程，放后台线程；root 不可用时把开关拨回去并落盘。
     * 由设置 Tab 的 refreshData 分支与开关回调调用。
     */
    private fun updateRootStatus() {
        Thread {
            val ok = RootShell.isAvailable()
            handler.post {
                if (!::tvRootStatus.isInitialized) return@post
                tvRootStatus.text = if (ok) "root 已就绪" else "root 不可用"
                miuixModel.rootStatus = tvRootStatus.text.toString()
                val cfg = dataManager.loadConfig()
                if (!ok && cfg.useRoot) {
                    cfg.useRoot = false
                    dataManager.saveConfig(cfg)
                    settingsSwitchSyncing = true
                    switchRoot.isChecked = false
                    settingsSwitchSyncing = false
                }
                miuixModel.useRoot = cfg.useRoot
            }
        }.start()
    }

    /** 刷新插件列表与摘要（worker 在线状态来自 bridge，能直接看出插件跑没跑起来）。 */
    private fun refreshPlugins() {
        val srv = PluginUi.server()
        if (srv == null) {
            pluginAdapter.submit(emptyList(), "服务未运行——上面的开关打开后再试")
            tvPluginSummary.text = "服务未运行"
            miuixModel.pluginSummary = "服务未运行"
            miuixModel.pluginMessage = "服务未运行——上面的开关打开后再试"
            miuixModel.plugins = emptyList()
            return
        }
        val rows = PluginUi.rows(srv)
        pluginAdapter.submit(rows, null)
        tvPluginSummary.text = if (rows.isEmpty()) "已安装 0 个插件" else "已安装 ${rows.size} 个插件"
        miuixModel.pluginSummary = tvPluginSummary.text.toString()
        miuixModel.pluginMessage = null
        miuixModel.plugins = rows
    }

    private fun confirmUninstallPlugin(id: String, name: String) {
        PluginUi.confirmUninstall(this, id, name) { message ->
            notifyUser(tabPlugins, message)
            refreshPlugins()
        }
    }

    /** root 开启确认后的探测（MD3 弹窗与 miuix WindowDialog 共用）。 */
    private fun enableRootWithProbe() {
        Thread {
            val ok = RootShell.isAvailable()
            handler.post {
                val cfg = dataManager.loadConfig()
                cfg.useRoot = ok
                dataManager.saveConfig(cfg)
                settingsSwitchSyncing = true
                switchRoot.isChecked = ok
                settingsSwitchSyncing = false
                notifyUser(
                    switchRoot,
                    if (ok) "已开启：命令将以 root 执行" else "root 不可用",
                    long = !ok,
                )
                updateRootStatus()
            }
        }.start()
    }

    private fun deleteDevice(device: TrustedDevice) {
        dataManager.removeTrustedDevice(device.deviceId)
        refreshData()
        notifyUser(rvDevices, "已删除设备")
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
        // 二次确认，别再直接删（也去掉「已删除」toast）
        MaterialAlertDialogBuilder(this)
            .setTitle("删除快速命令")
            .setMessage("确定删除这条快速命令？")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                dataManager.removeCommand(index)
                refreshData()
            }
            .show()
    }

    /**
     * 保存快速命令（MD3 弹窗与 miuix WindowDialog 共用）；
     * 返回 false = 校验失败（miuix 弹窗据此不关窗）。
     */
    private fun saveCommand(index: Int?, name: String, command: String): Boolean {
        if (name.isEmpty() || command.isEmpty()) {
            notifyUser(fabAddCommand, "名称和命令不能为空")
            return false
        }
        if (index != null) {
            dataManager.updateCommand(index, name, command)
        } else {
            dataManager.addCommand(name, command)
        }
        refreshData()
        return true
    }

    /**
     * 新建 / 编辑快速命令（同一个弹窗：index == null 表示新建）。
     * 编辑时预填现有名称与命令。miuix 模式下不走这里——由 MiuixApp 的
     * WindowDialog 渲染，保存逻辑共用 [saveCommand]。
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
                saveCommand(if (editing) index else null, name, command)
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
