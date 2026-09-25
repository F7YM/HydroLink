package com.hydro.link.server.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hydro.link.server.util.DataManager

/**
 * 开机自启：设置 Tab 里的开关（默认关）打开后，重启完成自动拉起 ServerService。
 * 开关没开就什么都不做——是否自启完全由用户显式选择
 * （老配置里没有 boot_start 字段，反序列化按 false 处理，即默认不自启）。
 *
 * BOOT_COMPLETED 是 protected broadcast，只有系统能发，manifest 里
 * exported="true" 不会带来伪造风险（Android 12+ 上 exported="false"
 * 收不到系统广播）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val enabled = try {
            DataManager.get(context).loadConfig().bootStart
        } catch (e: Exception) {
            false // 配置读不出来就别自启
        }
        if (!shouldStart(intent.action, enabled)) return
        try {
            ServerService.start(context, SERVER_PORT)
        } catch (e: Exception) {
            // 部分 ROM 有自启动管控 / 后台 FGS 限制，起不来不能让广播这边崩
            Log.w("HydroLink", "开机自启拉起服务失败: $e")
        }
    }

    companion object {
        /** 与 MainActivity 的默认端口一致。 */
        private const val SERVER_PORT = 9000

        /** 纯判定（JVM 单测直接跑）：只有"开机广播 + 用户开了开关"才启动。 */
        fun shouldStart(action: String?, bootStartEnabled: Boolean): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED && bootStartEnabled
    }
}
