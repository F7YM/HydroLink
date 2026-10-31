package com.hydro.link.server.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hydro.link.server.service.ServerService
import com.hydro.link.server.util.DataManager

/**
 * 接收配对请求「实时活动 / 流体云 / 超级岛 / 通知」上的「允许」「拒绝」按钮点击事件。
 */
class PairActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context?.applicationContext ?: return
        val act = intent?.action ?: return
        if (act != ACTION_PAIR_ACCEPT && act != ACTION_PAIR_REJECT) return

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)?.trim().orEmpty()
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)?.trim().orEmpty().ifEmpty { "Unknown" }
        if (deviceId.isEmpty()) return

        val dataManager = DataManager.get(ctx)
        dataManager.removePendingPair(deviceId)

        if (act == ACTION_PAIR_ACCEPT) {
            dataManager.addTrustedDevice(deviceId, deviceName)
            ServerService.instance?.getServer()?.addLog("已信任设备: ${deviceId.take(8)}... ($deviceName)", "success")
        } else {
            dataManager.addRejected(deviceId)
            ServerService.instance?.getServer()?.addLog("拒绝设备: ${deviceId.take(8)}...", "error")
        }

        // 立即销岛 / 移除通知
        PairNotificationManager.cancel(ctx, deviceId)
        onPairHandled?.invoke(deviceId)
    }

    companion object {
        const val ACTION_PAIR_ACCEPT = "com.hydro.link.server.ACTION_PAIR_ACCEPT"
        const val ACTION_PAIR_REJECT = "com.hydro.link.server.ACTION_PAIR_REJECT"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"

        /** 供前台 MainActivity 订阅，以便用户在岛上点允许/拒绝后界面立刻同步刷新。 */
        @Volatile
        var onPairHandled: ((String) -> Unit)? = null

        fun createPendingIntent(
            context: Context,
            action: String,
            deviceId: String,
            deviceName: String,
        ): PendingIntent {
            val intent = Intent(context, PairActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            val requestCode = "$action:$deviceId".hashCode()
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
