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
        ) -> Boolean
    )? = null

    /**
     * 确认弹窗。
     *
     * - [negative] 为 null 时只显示一个 [positive] 按钮；
     * - [onNegative] = 点否定钮（配对那种「允许/拒绝」都要落库的才需要）；返回键/点外面
     *   关窗不触发；
     * - [onConfirm] = 点肯定钮；
     * - [positiveEnabled] = false 时肯定钮灰显（版本不够、装不了）。
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
    ) {
        val handler = window
        if (handler != null && handler(title, message, positive, negative, onNegative, onConfirm, positiveEnabled)) {
            return
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
        if (negative != null) {
            builder.setNegativeButton(negative) { _, _ -> onNegative?.invoke() }
        }
        if (onConfirm != null && positiveEnabled) builder.setPositiveButton(positive) { _, _ -> onConfirm() }
        else builder.setPositiveButton(positive, null)
        builder.show()
    }
}
