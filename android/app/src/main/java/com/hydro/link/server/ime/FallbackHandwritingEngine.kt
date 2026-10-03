package com.hydro.link.server.ime

import kotlin.math.abs
import kotlin.math.atan2

/**
 * 轻量级离线笔画拓扑手写识别引擎（Fallback）。
 *
 * 当 ML Kit 手写模型未就绪或离线不可用时，基于笔画数、主方向分量及端点相对拓扑
 * 对常用汉字特征库进行距离度量，确保输入法 100% 具备稳定可用的离线兜底输出。
 */
object FallbackHandwritingEngine {

    // 常用基础笔画编码：1: 横(一), 2: 竖(丨), 3: 撇(丿), 4: 点/捺(丶\), 5: 折(乛)
    private data class StrokeFeature(
        val type: Int,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
    )

    // 常用汉字笔画拓扑简表（覆盖常见高频汉字，用于快速兜底）
    private val DICT: Map<String, List<Int>> = mapOf(
        "一" to listOf(1),
        "乙" to listOf(5),
        "二" to listOf(1, 1),
        "十" to listOf(1, 2),
        "丁" to listOf(1, 2),
        "厂" to listOf(1, 3),
        "七" to listOf(1, 5),
        "卜" to listOf(2, 4),
        "人" to listOf(3, 4),
        "入" to listOf(3, 4),
        "八" to listOf(3, 4),
        "九" to listOf(3, 5),
        "几" to listOf(3, 5),
        "儿" to listOf(3, 5),
        "了" to listOf(5, 2),
        "力" to listOf(5, 3),
        "乃" to listOf(5, 3),
        "又" to listOf(5, 4),
        "三" to listOf(1, 1, 1),
        "下" to listOf(1, 2, 4),
        "上" to listOf(2, 1, 1),
        "大" to listOf(1, 3, 4),
        "万" to listOf(1, 5, 3),
        "寸" to listOf(1, 2, 4),
        "工" to listOf(1, 2, 1),
        "土" to listOf(1, 2, 1),
        "干" to listOf(1, 1, 2),
        "口" to listOf(2, 5, 1),
        "山" to listOf(2, 5, 2),
        "巾" to listOf(2, 5, 2),
        "千" to listOf(3, 1, 2),
        "川" to listOf(3, 2, 2),
        "个" to listOf(3, 4, 2),
        "夕" to listOf(3, 5, 4),
        "久" to listOf(3, 5, 4),
        "么" to listOf(3, 5, 4),
        "广" to listOf(4, 1, 3),
        "门" to listOf(4, 2, 5),
        "女" to listOf(5, 3, 1),
        "小" to listOf(2, 3, 4),
        "子" to listOf(5, 5, 1),
        "也" to listOf(5, 2, 5),
        "木" to listOf(1, 2, 3, 4),
        "不" to listOf(1, 3, 2, 4),
        "王" to listOf(1, 1, 2, 1),
        "中" to listOf(2, 5, 1, 2),
        "水" to listOf(2, 5, 3, 4),
        "日" to listOf(2, 5, 1, 1),
        "月" to listOf(3, 5, 1, 1),
        "文" to listOf(4, 1, 3, 4),
        "手" to listOf(3, 1, 1, 5),
        "心" to listOf(4, 5, 4, 4),
        "风" to listOf(3, 5, 3, 4),
        "车" to listOf(1, 5, 1, 2),
        "白" to listOf(3, 2, 5, 1),
        "方" to listOf(4, 1, 5, 3),
        "分" to listOf(3, 4, 5, 3),
        "化" to listOf(3, 2, 3, 5),
        "生" to listOf(3, 1, 2, 1, 1),
        "白" to listOf(3, 2, 5, 1, 1),
        "出" to listOf(5, 2, 1, 5, 2),
        "正" to listOf(1, 2, 1, 2, 1),
        "世" to listOf(1, 2, 2, 1, 5),
        "本" to listOf(1, 2, 3, 4, 1),
        "可" to listOf(1, 2, 5, 1, 2),
        "左" to listOf(1, 3, 1, 2, 1),
        "右" to listOf(1, 3, 2, 5, 1),
        "石" to listOf(1, 3, 2, 5, 1),
        "市" to listOf(4, 1, 2, 5, 2),
        "布" to listOf(1, 3, 2, 5, 2),
        "平" to listOf(1, 4, 3, 1, 2),
        "东" to listOf(1, 5, 3, 4, 2),
        "电" to listOf(2, 5, 1, 1, 5),
        "北" to listOf(2, 1, 3, 3, 5),
        "四" to listOf(2, 5, 3, 5, 1),
        "半" to listOf(4, 3, 1, 1, 2),
        "头" to listOf(4, 4, 1, 3, 4),
        "你" to listOf(3, 2, 3, 5, 2, 3, 4),
        "好" to listOf(5, 3, 1, 5, 5, 1),
        "我" to listOf(3, 1, 2, 1, 5, 3, 4),
        "他" to listOf(3, 2, 5, 2, 5),
        "们" to listOf(3, 2, 4, 2, 5),
        "来" to listOf(1, 4, 3, 1, 2, 3, 4),
        "会" to listOf(3, 4, 1, 1, 5, 4),
        "和" to listOf(3, 1, 2, 3, 4, 2, 5, 1),
        "国" to listOf(2, 5, 1, 1, 2, 1, 4, 1),
        "学" to listOf(4, 4, 3, 4, 5, 5, 5, 1),
    )

    fun recognize(strokes: List<List<Pair<Float, Float>>>, maxCandidates: Int): List<String> {
        val count = strokes.size
        val extractedTypes = strokes.map { classifyStroke(it) }

        // 按笔画数相差 <= 1 的汉字优先过滤匹配
        val candidatesWithScore = mutableListOf<Pair<String, Double>>()

        for ((hanzi, standardStrokes) in DICT) {
            val strokeDiff = abs(standardStrokes.size - count)
            if (strokeDiff > 2) continue

            // 计算笔画序列相似度
            var matchCount = 0
            val minLen = minOf(extractedTypes.size, standardStrokes.size)
            for (i in 0 until minLen) {
                if (extractedTypes[i] == standardStrokes[i]) {
                    matchCount++
                }
            }

            val score = (matchCount * 2.0) - (strokeDiff * 1.5)
            if (score > 0) {
                candidatesWithScore.add(hanzi to score)
            }
        }

        candidatesWithScore.sortByDescending { it.second }
        val results = candidatesWithScore.map { it.first }.distinct()
        return if (results.isNotEmpty()) {
            results.take(maxCandidates)
        } else {
            // 极低匹配度保底输出常用候选
            when (count) {
                1 -> listOf("一", "乙")
                2 -> listOf("十", "人", "二", "八", "七", "九", "几", "力")
                3 -> listOf("三", "大", "小", "口", "山", "工", "上", "下")
                4 -> listOf("木", "中", "日", "月", "水", "不", "王", "文")
                5 -> listOf("生", "正", "出", "本", "白", "可", "左", "右")
                6 -> listOf("好", "你", "他", "会", "自", "行")
                7 -> listOf("你", "我", "来", "和")
                else -> listOf("好", "你", "我", "们", "大", "中", "小", "国")
            }.take(maxCandidates)
        }
    }

    /**
     * 将采样点折线归类为基础五笔笔画类型：
     * 1: 横, 2: 竖, 3: 撇, 4: 捺/点, 5: 折
     */
    private fun classifyStroke(points: List<Pair<Float, Float>>): Int {
        if (points.size < 2) return 4 // 单点当作“点”
        val start = points.first()
        val end = points.last()

        val dx = end.first - start.first
        val dy = end.second - start.second
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length < 10) return 4 // 位移很小视为“点”

        // 计算是否有明显折角（折笔判定）
        if (points.size >= 4) {
            val mid = points[points.size / 2]
            val v1x = mid.first - start.first
            val v1y = mid.second - start.second
            val v2x = end.first - mid.first
            val v2y = end.second - mid.second
            val angle1 = atan2(v1y.toDouble(), v1x.toDouble())
            val angle2 = atan2(v2y.toDouble(), v2x.toDouble())
            var diff = abs(angle1 - angle2)
            if (diff > Math.PI) diff = 2 * Math.PI - diff
            if (diff > 0.8) { // 超过约 45 度转折
                return 5 // 折
            }
        }

        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // -180 ~ 180
        return when {
            angle in -35.0..35.0 -> 1 // 横
            angle in 55.0..125.0 -> 2 // 竖 (从上往下)
            angle in 125.0..180.0 || angle in -180.0..-145.0 -> 3 // 撇
            angle in 35.0..55.0 -> 4 // 捺
            else -> 1
        }
    }
}
