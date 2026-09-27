package com.hydro.link.server.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 子页转场规格（全应用唯一一份）。
 *
 * 之前每处自己写：插件市场 NavHost 是 240ms 滑入 + 200ms 淡入，MD3 授权器二级页是
 * `AnimatedVisibility` 的默认（spring）规格，miuix Tab 是 180/120 纯淡入——三处手感
 * 不一致，用户能直接感觉出「这一页的动画怎么和别的不一样」。
 *
 * 约定：
 * - **子页**（详情页、授权器选择页这类整页覆盖）→ 用这里的 slide + fade；
 * - **Tab 之间**（miuix 底栏/普通底栏切换）→ 保持纯淡入淡出，那是 InstallerX 的行为，
 *   不要换成滑动（横向滑会让人以为能左右切 Tab）。
 */
object PageTransitions {

    /** 子页推入：内容从右滑入 + 轻微淡入。 */
    val enter: EnterTransition =
        slideInHorizontally(tween(DURATION)) { it } + fadeIn(tween(FADE_IN))

    /** 推入时当前页的退场：原地淡出，不位移（否则两层一起动会晕）。 */
    val exit: ExitTransition = fadeOut(tween(FADE_OUT))

    /** 子页返回：淡入。 */
    val popEnter: EnterTransition = fadeIn(tween(FADE_IN))

    /** 返回时当前页退场：往右滑出 + 淡出。 */
    val popExit: ExitTransition =
        slideOutHorizontally(tween(DURATION)) { it } + fadeOut(tween(FADE_OUT))

    private const val DURATION = 240
    private const val FADE_IN = 200
    private const val FADE_OUT = 160
}
