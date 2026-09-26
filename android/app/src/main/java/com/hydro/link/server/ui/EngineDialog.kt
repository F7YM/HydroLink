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
     */
    var window: (
        (
            title: String,
            message: String,
            positive: String,
            negative: String?,
            onConfirm: (() -> Unit)?,
            onNegative: (() -> Unit)?,
        ) -> Boolean
    )? = null

    /**
     * 确认弹窗：[negative] 为 null 时只有一个 [positive] 按钮。
     * [onConfirm] = 点肯定钮，[onNegative] = 点否定钮（单纯关窗/返回键关闭不触发，
     * 配对这种「允许/拒绝」两个动作都要落库的场景才用它）。
     */
    fun confirm(
        activity: Activity,
        title: String,
        message: String,
        positive: String,
        negative: String?,
        onConfirm: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
    ) {
        val handler = window
        if (handler != null && handler(title, message, positive, negative, onConfirm, onNegative)) return
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
        if (negative != null) {
            builder.setNegativeButton(negative) { _, _ -> onNegative?.invoke() }
        }
        if (onConfirm != null) builder.setPositiveButton(positive) { _, _ -> onConfirm() }
        else builder.setPositiveButton(positive, null)
        builder.show()
    }
}
