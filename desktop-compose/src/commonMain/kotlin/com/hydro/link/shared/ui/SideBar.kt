package com.hydro.link.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 左侧悬浮侧栏：图标 + 文字**左右并列**的一列（Apple 开发者站那种浮在内容左边的面板）。
 *
 * 不用手机那套底部 Tab 栏：桌面窗口是横向的，导航在左边更顺手，底部一整条胶囊还会把
 * 内容压得很窄。侧栏上下铺满窗口左侧，按钮左右铺满侧栏宽度。
 *
 * 面板本体是半透明容器色（**没有模糊**）：miuix-blur 的录制/采样路径在这台机器上会
 * 把 JVM 打成 SIGSEGV（见提交说明），桌面不冒险。
 */
@Composable
fun SideBar(
    selected: HomeTab,
    containerColor: Color,
    accentColor: Color,
    contentColor: Color,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
    /** 面板宽度：由宿主按窗口宽度算好传进来（见 [adaptiveSideBarWidth]）。 */
    width: Dp = SideBarWidth,
    /**
     * 面板描边（null = 不描边）。
     *
     * MD3 侧必须描一道边：深色下 `surfaceContainerHigh` 和页面底色只差一档，
     * 不描边的话整个面板糊在背景里（对比度很差，浮不起来）。miuix 侧不描边——
     * 它的 surfaceContainer 本身就比页面底色深一档。
     */
    border: Color? = null,
) {
    // 外层圆角与内部按钮「妥协」：内部行是纯圆（药丸，半径≈行高一半 ≈24dp），
    // 外层用 32dp——比内部略大一点显得有层次，又不能大到变成整条药丸（那个太夸张）。
    val shape = RoundedCornerShape(32.dp)
    // 上下左右都留 PageEdge：与页面内容的顶部留白一致（之前侧栏 12dp、页面 8dp，
    // 两边对不齐，一眼就能看出来）
    Box(modifier = modifier.padding(PageEdge)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(width)
                .selectableGroup()
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(radius = 16.dp, color = Color.Black, alpha = 0.22f),
                )
                .clip(shape)
                .background(containerColor)
                .then(
                    if (border != null) {
                        Modifier.border(1.dp, border, shape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HomeTab.entries.forEach { tab ->
                SideBarRow(
                    tab = tab,
                    selected = tab == selected,
                    accent = accentColor,
                    content = contentColor,
                    onClick = { onSelect(tab) },
                )
            }
        }
    }
}

/** 页面内容与窗口边缘的统一留白（侧栏与卡片区共用，两边才对得齐）。 */
val PageEdge = 16.dp

/**
 * 页面内容的最大宽度：窗口拉宽时列表保持在中间，不贴着窗口边拉成横条。
 * 两个引擎共用同一个值，否则会出现「一个居中一个靠左」的不一致。
 */
val PageMaxWidth = 880.dp

/** 侧栏的基准宽度（内容区用它让位；实际宽度由 [adaptiveSideBarWidth] 按窗口算）。 */
val SideBarWidth = 216.dp

/** 内容区左侧为侧栏预留的宽度（默认值，实际由宿主按侧栏宽度算）。 */
val SideBarSpace = SideBarWidth + PageEdge * 2

/**
 * 侧栏宽度随窗口自适应。
 *
 * 固定宽度在最大化时显得又窄又空（内容全堆在左边），窄窗口又挤。这里按窗口宽度的
 * 17% 连续计算，再夹到 216dp~340dp：分档的差异太小（只差二三十 dp），肉眼看不出来，
 * 像是"没变"。
 */
fun adaptiveSideBarWidth(windowWidth: Dp): Dp =
    (windowWidth * 0.17f).coerceIn(216.dp, 340.dp)

@Composable
private fun SideBarRow(
    tab: HomeTab,
    selected: Boolean,
    accent: Color,
    content: Color,
    onClick: () -> Unit,
) {
    // 纯圆：两端的圆角半径拉到一半（CircleShape 对非正方形就是药丸形），
    // 不再是之前那个 18dp 的小圆角矩形
    val rowShape = CircleShape
    val tint = if (selected) accent else content
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
            }
            .clip(rowShape)
            .clickable(onClick = onClick)
            .background(if (selected) accent.copy(alpha = 0.15f) else Color.Transparent, rowShape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
        Text(
            text = tab.title,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
