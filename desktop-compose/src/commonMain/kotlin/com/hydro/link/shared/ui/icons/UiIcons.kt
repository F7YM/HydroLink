package com.hydro.link.shared.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 应用图标（**直接来自安卓端** `android/app/src/main/res/drawable` 里的 vector xml，逐条 pathData 搬过来）。
 *
 * 为什么不是 `Res.drawable` / 也不是 Material Icons：
 * - Material Icons 是尖角实心那套，和安卓端（圆头描边，`ic_check` 就是 2.6 圆头描边）不是一路，
 *   换上以后对号明显变形；
 * - composeResources 的 vector XML 在本工程的 KMP 配置下没接进编译（访问器生成了但
 *   desktop 编译看不到），所以直接把 pathData 生成为 [ImageVector]，零资源管线、跨端一致。
 *
 * 注意：Kotlin 块注释可嵌套，写注释时别在里面出现斜杠加星号（会开一个永不闭合的
 * 嵌套注释，编译报 Unclosed comment）。
 *
 * 生成方式见提交说明；改安卓端图标后重新跑一次同样的转换，不要手改这里。
 */
private fun icon(
    name: String,
    block: ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply(block).build()

/** 追加一条 path：安卓 vector 的 pathData 交给 PathParser 解析，参数语义一一对应。 */
private fun ImageVector.Builder.path(
    d: String,
    fill: Color?,
    stroke: Color? = null,
    strokeWidth: Float = 0f,
    cap: StrokeCap = StrokeCap.Butt,
    join: StrokeJoin = StrokeJoin.Miter,
) {
    addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        pathFillType = PathFillType.NonZero,
        name = "",
        fill = fill?.let { SolidColor(it) },
        fillAlpha = 1f,
        stroke = stroke?.let { SolidColor(it) },
        strokeAlpha = 1f,
        strokeLineWidth = strokeWidth,
        strokeLineCap = cap,
        strokeLineJoin = join,
    )
}


/** `ic_nav_status.xml`（安卓端） */
val IcNavStatus: ImageVector = icon("ic_nav_status") {
    path(
        d = "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_nav_devices.xml`（安卓端） */
val IcNavDevices: ImageVector = icon("ic_nav_devices") {
    path(
        d = "M4,6h18V4H4c-1.1,0 -2,0.9 -2,2v11H0v3h14v-3H4V6zM23,8h-6c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1V9c0,-0.55 -0.45,-1 -1,-1zM22,17h-4v-7h4v7z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_nav_commands.xml`（安卓端） */
val IcNavCommands: ImageVector = icon("ic_nav_commands") {
    path(
        d = "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_nav_plugins.xml`（安卓端） */
val IcNavPlugins: ImageVector = icon("ic_nav_plugins") {
    path(
        d = "M20.5,11H19V7c0,-1.1 -0.9,-2 -2,-2h-4V3.5C13,2.12 11.88,1 10.5,1S8,2.12 8,3.5V5H4C2.9,5 2.01,5.9 2.01,7v3.8H3.5c1.49,0 2.7,1.21 2.7,2.7s-1.21,2.7 -2.7,2.7H2V20c0,1.1 0.9,2 2,2h3.8v-1.5c0,-1.49 1.21,-2.7 2.7,-2.7s2.7,1.21 2.7,2.7V22H17c1.1,0 2,-0.9 2,-2v-4h1.5c1.38,0 2.5,-1.12 2.5,-2.5S21.88,11 20.5,11z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_settings.xml`（安卓端） */
val IcSettings: ImageVector = icon("ic_settings") {
    path(
        d = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94l-0.36,-2.54c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41l-0.36,2.54c-0.59,0.24 -1.13,0.57 -1.62,0.94l-2.39,-0.96c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87c-0.12,0.21 -0.08,0.47 0.12,0.61l2.03,1.58c-0.05,0.3 -0.09,0.63 -0.09,0.94s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61l-2.01,-1.58zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_add.xml`（安卓端） */
val IcAdd: ImageVector = icon("ic_add") {
    path(
        d = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_check.xml`（安卓端） */
val IcCheck: ImageVector = icon("ic_check") {
    // 同上：原样照安卓端
    path(
        d = "M5,12.5 L10,17.5 L19,7.5",
        fill = null,
        stroke = Color.White,
        strokeWidth = 2.6f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
}

/** `ic_close.xml`（安卓端） */
val IcClose: ImageVector = icon("ic_close") {
    path(
        d = "M6.8,6.8 L17.2,17.2 M17.2,6.8 L6.8,17.2",
        fill = null,
        stroke = Color.White,
        strokeWidth = 2.6f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
}

/** `ic_delete.xml`（安卓端） */
val IcDelete: ImageVector = icon("ic_delete") {
    path(
        d = "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_edit.xml`（安卓端） */
val IcEdit: ImageVector = icon("ic_edit") {
    path(
        d = "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_check_circle_outline.xml`（安卓端） */
val IcCheckCircleOutline: ImageVector = icon("ic_check_circle_outline") {
    // 圆环：安卓端那版是两段 A 命令，在底部（12,20.6）有 180° 的接缝，描边到接缝会顶出尖。
    // 改成单条连续的四段三次贝塞尔圆。**结尾不能写 Z**：闭合点等于起点，零长度闭合线
    // 会被当成一个 round join，反过来又在起点处顶出一个缺口（这个坑踩过一次）。
    // 起点放在 45° 方向（右下），万一还有瑕疵也不在显眼的顶部。
    path(
        d = "M18.08,18.08 C14.72,21.44 9.28,21.44 5.92,18.08 C2.56,14.72 2.56,9.28 5.92,5.92 C9.28,2.56 14.72,2.56 18.08,5.92 C21.44,9.28 21.44,14.72 18.08,18.08",
        fill = null,
        stroke = Color.White,
        strokeWidth = 2.4f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    // 对号：**原样照安卓端**（不烘圆角、不平移）。安卓那版靠 strokeLineJoin="round"
    // 把 V 尖磨圆，圆不圆要看桌面渲染认不认这个 join——直接在窗口上看，别再靠推断改几何。
    path(
        d = "M8.1,12.35 L10.7,14.95 L15.95,9.5",
        fill = null,
        stroke = Color.White,
        strokeWidth = 2.4f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
}

/** `ic_error_outline.xml`（安卓端） */
val IcErrorOutline: ImageVector = icon("ic_error_outline") {
    // 圆环同样是不闭合的单条连续路径（理由同 ic_check_circle_outline）
    path(
        d = "M18.08,18.08 C14.72,21.44 9.28,21.44 5.92,18.08 C2.56,14.72 2.56,9.28 5.92,5.92 C9.28,2.56 14.72,2.56 18.08,5.92 C21.44,9.28 21.44,14.72 18.08,18.08",
        fill = null,
        stroke = Color.White,
        strokeWidth = 2.4f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    path(
        d = "M12,7.8 L12,13.2",
        fill = null,
        stroke = Color.White,
        strokeWidth = 2.4f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    path(
        d = "M12,16.4 L12,16.5",
        fill = null,
        stroke = Color.White,
        strokeWidth = 2.6f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
}

/** `ic_chevron_right.xml`（安卓端） */
val IcChevronRight: ImageVector = icon("ic_chevron_right") {
    path(
        d = "M9.5,6 L15.5,12 L9.5,18",
        fill = null,
        stroke = Color.White,
        strokeWidth = 2.2f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
}

/** 下拉三角指示图标 */
val IcArrowDropDown: ImageVector = icon("ic_arrow_drop_down") {
    path(
        d = "M7,10 L12,15 L17,10 Z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_plugin_refresh.xml`（安卓端） */
val IcPluginRefresh: ImageVector = icon("ic_plugin_refresh") {
    path(
        d = "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -8,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4c-3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_plugin_ui.xml`（安卓端） */
val IcPluginUi: ImageVector = icon("ic_plugin_ui") {
    path(
        d = "M4,4h16c1.1,0 2,0.9 2,2v12c0,1.1 -0.9,2 -2,2H4c-1.1,0 -2,-0.9 -2,-2V6c0,-1.1 0.9,-2 2,-2zM4,8v10h16V8H4z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_plugin_delete.xml`（安卓端） */
val IcPluginDelete: ImageVector = icon("ic_plugin_delete") {
    path(
        d = "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_upgrade.xml`（安卓端） */
val IcUpgrade: ImageVector = icon("ic_upgrade") {
    path(
        d = "M12,4L12,1L8,5l4,4L12,6c3.31,0 6,2.69 6,6c0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 20,12C20,7.58 16.42,4 12,4zM12,18c-3.31,0 -6,-2.69 -6,-6c0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4l-4,-4L12,18z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

/** `ic_arrow_back.xml`（安卓端） */
val IcArrowBack: ImageVector = icon("ic_arrow_back") {
    path(
        d = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
        fill = Color.White,
        stroke = null,
        strokeWidth = 0f,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )
}

// ---------------------------------------------------------------------------
// 应用自有图标：安卓端 res 里**没有**对应矢量文件的（不是从安卓搬来的）。
// 市场 Tab 用购物袋：原来借了 ic_plugin_ui（一个方框，和「插件界面」撞了）。
// ---------------------------------------------------------------------------

/** 购物袋（市场 Tab）。 */
val IcStore: ImageVector = icon("ic_store") {
    path(
        d = "M7,18c-1.1,0 -1.99,0.9 -1.99,2S5.9,22 7,22s2,-0.9 2,-2 -0.9,-2 -2,-2z" +
            "M1,2v2h2l3.6,7.59 -1.35,2.45C5.09,14.32 5,14.65 5,15c0,1.1 0.9,2 2,2h12v-2" +
            "H7.42c-0.14,0 -0.25,-0.11 -0.25,-0.25l0.03,-0.12 0.9,-1.63h7.45c0.75,0 1.41,-0.41 " +
            "1.75,-1.03L20.88,5H5.21l-0.94,-2H1zM17,18c-1.1,0 -1.99,0.9 -1.99,2s0.89,2 1.99,2 " +
            "2,-0.9 2,-2 -0.9,-2 -2,-2z",
        fill = Color.White,
    )
}
