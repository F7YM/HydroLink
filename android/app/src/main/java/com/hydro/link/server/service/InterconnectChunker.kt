package com.hydro.link.server.service

import com.google.gson.JsonObject

/**
 * 互联通道回包分片（无 Android 依赖，JVM 单测见 InterconnectChunkerTest）。
 *
 * XMS 互联单条消息只有十几 KB 的上限，超了 `sendMessage` 直接失败、手表那边干等到
 * 超时——插件视图树、图片/GIF 分段（base64）都轻松超过。所以大回包拆成
 * `{"type":"chunk","id":<请求 id>,"seq":i,"total":n,"data":"<原 JSON 文本的一段>"}`，
 * 手表按 seq 拼回原文再当一条普通消息处理（`interconnectService._handleChunk`）。
 *
 * 切分按"编码后的字节数"计：data 作为 JSON 字符串还要再转义一次（`"`→`\"`），
 * 中文是 3 字节；代理对（emoji）绝不从中间劈开。
 */
object InterconnectChunker {
    /** 单条消息的字节预算（留足余量，实测上限约 16KB）。 */
    const val MESSAGE_BUDGET = 12 * 1024

    /** chunk 外壳（type/id/seq/total 与引号）预留的字节数。 */
    private const val ENVELOPE_RESERVE = 160

    fun chunk(id: String, text: String, budget: Int = MESSAGE_BUDGET): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= budget) return listOf(text)
        val dataBudget = budget - ENVELOPE_RESERVE - id.length * 6
        require(dataBudget > 16) { "budget too small" }

        val slices = ArrayList<String>()
        var start = 0
        var cost = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val step = if (pair) 2 else 1
            val charCost = if (pair) 4 else encodedCost(c)
            if (cost + charCost > dataBudget && i > start) {
                slices.add(text.substring(start, i))
                start = i
                cost = 0
            }
            cost += charCost
            i += step
        }
        if (start < text.length) slices.add(text.substring(start))

        return slices.mapIndexed { seq, data ->
            JsonObject().apply {
                addProperty("type", "chunk")
                addProperty("id", id)
                addProperty("seq", seq)
                addProperty("total", slices.size)
                addProperty("data", data)
            }.toString()
        }
    }

    /** 一个字符放进 JSON 字符串后的 UTF-8 字节数（按最坏的转义估计）。 */
    private fun encodedCost(c: Char): Int = when {
        c.code < 0x20 || c == '\u2028' || c == '\u2029' -> 6
        c == '"' || c == '\\' -> 2
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        c.isSurrogate() -> 6 // 落单的代理项会被转义成 \uXXXX
        else -> 3
    }
}
