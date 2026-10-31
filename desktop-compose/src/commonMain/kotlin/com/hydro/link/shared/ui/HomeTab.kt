package com.hydro.link.shared.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.hydro.link.shared.ui.icons.IcNavCommands
import com.hydro.link.shared.ui.icons.IcNavDevices
import com.hydro.link.shared.ui.icons.IcNavPlugins
import com.hydro.link.shared.ui.icons.IcNavStatus
import com.hydro.link.shared.ui.icons.IcStore
import com.hydro.link.shared.ui.icons.IcSettings

/**
 * 五个 Tab 的定义（顺序、文案、图标与安卓端一致）。
 *
 * 图标是安卓端那套矢量图（见 `icons/UiIcons.kt`，由安卓端 res/drawable 下那批 vector xml
 * 的 pathData 生成），不是 Material Icons——Material 是尖角实心那套，换上去连对号都变形。
 */
enum class HomeTab(val title: String, val icon: ImageVector) {
    Status("状态", IcNavStatus),
    Devices("设备", IcNavDevices),
    Commands("命令", IcNavCommands),
    Plugins("插件", IcNavPlugins),
    /** 插件市场紧跟在插件后面（两个都是插件相关的东西，不要被设置隔开）。 */
    Market("插件市场", IcStore),
    Settings("设置", IcSettings),
}
