package com.hydro.link.server.ui

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 引擎感知的确认弹窗。
 *
 * 主界面与各独立页里所有 View 侧弹窗都要按当前 UI 引擎画：MD3 用
 * [MaterialAlertDialogBuilder]，miuix 用 WindowDialog——后者是 Compose 组件，
 * 必须由界面（MainActivity 的 MiuixModel）提供渲染位，所以这里留一个钩子，
 * MainActivity 在 miuix 模式下接管、MD3 模式下返回 false 走默认实现。
 *
 * 插件的安装/卸载/卡片详情、删除快速命令等都走这里，避免 miuix 界面里冒出
 * MD 风格对话框（曾发生过，属于双引擎重做时的漏网之鱼）。提示一律走系统 Toast。
 */
object EngineDialog {

    /**
     * miuix 引擎的接管钩子：返回 true = 已交给 WindowDialog 渲染。
     * null / 返回 false = 用 [MaterialAlertDialogBuilder]。只在主线程调用。
     *
     * 参数顺序与 [confirm] 一致，`onConfirm` 刻意排在最后：调用点写尾随 lambda 落到的是
     * 「确定」而不是「否定」——把「卸载/删除」这种破坏性动作写成尾随 lambda 过一次当，
     * 结果点「取消」把插件卸了。
     */
    var window: (
        (
            title: String,
            message: String,
            positive: String,
            negative: String?,
            onNegative: (() -> Unit)?,
            onConfirm: (() -> Unit)?,
            positiveEnabled: Boolean,
            countdownSeconds: Int,
            dangerWarning: String?,
        ) -> Boolean
    )? = null

    /**
     * 确认弹窗。
     *
     * - [negative] 为 null 时只显示一个 [positive] 按钮；
     * - [onNegative] = 点否定钮（配对那种「允许/拒绝」都要落库的才需要）；返回键/点外面
     *   关窗不触发；
     * - [onConfirm] = 点肯定钮；
     * - [positiveEnabled] = false 时肯定钮灰显（版本不够、装不了）；
     * - [countdownSeconds] = 倒计时秒数（高危权限安装强制等待）：> 0 时肯定钮禁用并显示倒计时；
     * - [dangerWarning] = 高危警示文案（红字突出显示）。
     */
    fun confirm(
        activity: Activity,
        title: String,
        message: String,
        positive: String,
        negative: String?,
        onNegative: (() -> Unit)? = null,
        onConfirm: (() -> Unit)? = null,
        positiveEnabled: Boolean = true,
        countdownSeconds: Int = 0,
        dangerWarning: String? = null,
    ) {
        val handler = window
        if (handler != null && handler(title, message, positive, negative, onNegative, onConfirm, positiveEnabled, countdownSeconds, dangerWarning)) {
            return
        }
        val fullMessage: CharSequence = if (dangerWarning != null) {
            val ssb = android.text.SpannableStringBuilder()
            val start = ssb.length
            ssb.append(dangerWarning).append("\n\n")
            ssb.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#E53935")),
                start,
                ssb.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start,
                ssb.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.append(message)
            ssb
        } else {
            message
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(fullMessage)
        if (negative != null) {
            builder.setNegativeButton(negative) { _, _ -> onNegative?.invoke() }
        }
        val initialText = if (countdownSeconds > 0) "$positive (${countdownSeconds}s)" else positive
        builder.setPositiveButton(initialText, null)
        val dialog = builder.create()
        dialog.show()

        val posBtn = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        if (countdownSeconds > 0) {
            posBtn.isEnabled = false
            val handlerThread = android.os.Handler(android.os.Looper.getMainLooper())
            var remaining = countdownSeconds
            val runnable = object : Runnable {
                override fun run() {
                    remaining--
                    if (dialog.isShowing) {
                        if (remaining > 0) {
                            posBtn.text = "$positive (${remaining}s)"
                            handlerThread.postDelayed(this, 1000)
                        } else {
                            posBtn.text = positive
                            if (positiveEnabled) {
                                posBtn.isEnabled = true
                                posBtn.setOnClickListener {
                                    dialog.dismiss()
                                    onConfirm?.invoke()
                                }
                            }
                        }
                    }
                }
            }
            handlerThread.postDelayed(runnable, 1000)
        } else {
            posBtn.isEnabled = positiveEnabled
            if (positiveEnabled) {
                posBtn.setOnClickListener {
                    dialog.dismiss()
                    onConfirm?.invoke()
                }
            }
        }
    }
}
