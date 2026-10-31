package com.hydro.link.server.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hydro.link.server.MainActivity
import com.hydro.link.server.R
import com.hydro.link.server.util.DataManager

/**
 * 配对请求通知与「实时活动（Live Update / 流体云 / 超级岛）」管理器。
 *
 * 仅在收到未信任设备的配对请求时触发：
 * - 样式为 `live`（实时活动，默认）：
 *   · 统一走 Android 16+ 原生 Live Update（Promoted Ongoing Notification：
 *     `POST_PROMOTED_NOTIFICATIONS` + `setRequestPromotedOngoing(true)` +
 *     `setShortCriticalText("配对请求")` + `BigTextStyle`），
 *     在 ColorOS 16 流体云、HyperOS 3 超级岛及原生 Android 16+ 实时活动上直接展示：
 *     胶囊态展示「配对请求」，展开态展示「配对请求 · <设备名>」、完整 `<device_id>` 与「允许 / 拒绝」操作；
 *   · 其他系统：自动降级为高优先级常驻通知。
 * - 样式为 `standard`（标准通知）：
 *   · 发送常规高优先级横幅通知，包含完整设备 ID 与「允许 / 拒绝」按钮。
 */
object PairNotificationManager {

    const val CHANNEL_PAIR_LIVE = "hydro_pair_live_channel"
    const val CHANNEL_PAIR_STANDARD = "hydro_pair_standard_channel"

    const val STYLE_LIVE = "live"
    const val STYLE_STANDARD = "standard"

    const val SHORT_CRITICAL_TEXT = "配对请求"
    private const val BRAND_COLOR = 0xFF006EFF.toInt()

    /** 纯函数：计算通知 ID（同一设备 ID 固定对应同一条通知，便于更新与销岛）。 */
    fun notificationIdFor(deviceId: String): Int =
        ("hydro_pair:$deviceId".hashCode() and Int.MAX_VALUE).coerceAtLeast(1000)

    /** 纯函数：生成展开态标题。 */
    fun formatTitle(deviceName: String): String {
        val clean = deviceName.trim()
        return if (clean.isEmpty() || clean.equals("Unknown", ignoreCase = true)) {
            "收到配对请求"
        } else {
            "配对请求 · $clean"
        }
    }

    /** 纯函数：生成展开态正文（直接显示完整设备 ID，不加前缀）。 */
    fun formatContent(deviceId: String): String = deviceId.trim()

    fun ensureChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        val liveChannel = NotificationChannelCompat.Builder(
            CHANNEL_PAIR_LIVE,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName("配对请求实时活动")
            .setDescription("收到手表配对请求时以实时活动/流体云/超级岛形式展示")
            .build()

        val standardChannel = NotificationChannelCompat.Builder(
            CHANNEL_PAIR_STANDARD,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName("配对请求通知")
            .setDescription("收到手表配对请求时弹出通知询问允许或拒绝")
            .build()

        nm.createNotificationChannel(liveChannel)
        nm.createNotificationChannel(standardChannel)
    }

    @SuppressLint("MissingPermission")
    fun notifyPairRequest(context: Context, deviceId: String, deviceName: String) {
        val appCtx = context.applicationContext
        val nm = NotificationManagerCompat.from(appCtx)
        if (!nm.areNotificationsEnabled()) return

        ensureChannels(appCtx)
        val config = DataManager.get(appCtx).loadConfig()
        val useLive = config.pairNotificationStyle != STYLE_STANDARD

        val notification = if (useLive) {
            buildLiveActivityNotification(appCtx, deviceId, deviceName)
        } else {
            buildStandardNotification(appCtx, deviceId, deviceName)
        }

        nm.notify(notificationIdFor(deviceId), notification)
    }

    /** 处理完配对请求（允许/拒绝）后立即销岛/清除通知。 */
    fun cancel(context: Context, deviceId: String) {
        try {
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(notificationIdFor(deviceId))
        } catch (_: Throwable) {
        }
    }

    private fun buildStandardNotification(
        context: Context,
        deviceId: String,
        deviceName: String,
    ): Notification {
        val title = formatTitle(deviceName)
        val content = formatContent(deviceId)
        val openIntent = createOpenIntent(context, deviceId)
        val acceptIntent = PairActionReceiver.createPendingIntent(
            context,
            PairActionReceiver.ACTION_PAIR_ACCEPT,
            deviceId,
            deviceName,
        )
        val rejectIntent = PairActionReceiver.createPendingIntent(
            context,
            PairActionReceiver.ACTION_PAIR_REJECT,
            deviceId,
            deviceName,
        )

        return NotificationCompat.Builder(context, CHANNEL_PAIR_STANDARD)
            .setSmallIcon(R.drawable.ic_card_link)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_check, "允许", acceptIntent)
            .addAction(R.drawable.ic_close, "拒绝", rejectIntent)
            .build()
    }

    /**
     * Android 16+ 原生 Live Update（Promoted Ongoing）构建器。
     *
     * 满足 Android 16 Live Update / ColorOS 流体云 / HyperOS 超级岛的准入条件：
     * 1. POST_PROMOTED_NOTIFICATIONS 权限
     * 2. ongoing = true
     * 3. requestPromotedOngoing = true
     * 4. shortCriticalText = "配对请求"（胶囊态短文本，<= 7 字）
     * 5. BigTextStyle 富文本样式（无进度条，完整展示设备 ID）
     */
    private fun buildLiveActivityNotification(
        context: Context,
        deviceId: String,
        deviceName: String,
    ): Notification {
        val title = formatTitle(deviceName)
        val content = formatContent(deviceId)
        val openIntent = createOpenIntent(context, deviceId)
        val acceptIntent = PairActionReceiver.createPendingIntent(
            context,
            PairActionReceiver.ACTION_PAIR_ACCEPT,
            deviceId,
            deviceName,
        )
        val rejectIntent = PairActionReceiver.createPendingIntent(
            context,
            PairActionReceiver.ACTION_PAIR_REJECT,
            deviceId,
            deviceName,
        )

        return NotificationCompat.Builder(context, CHANNEL_PAIR_LIVE)
            .setSmallIcon(R.drawable.ic_card_link)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(content)
            .setShortCriticalText(SHORT_CRITICAL_TEXT)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_check, "允许", acceptIntent)
            .addAction(R.drawable.ic_close, "拒绝", rejectIntent)
            .build()
    }

    private fun createOpenIntent(context: Context, deviceId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            ("open:$deviceId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
