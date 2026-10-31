package com.hydro.link.server.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterconnectChunkerTest {

    private fun reassemble(messages: List<String>): String {
        val parts = messages.map { JsonParser.parseString(it).asJsonObject }
        val total = parts.first().get("total").asInt
        assertEquals(total, parts.size)
        return parts.sortedBy { it.get("seq").asInt }.joinToString("") { it.get("data").asString }
    }

    private fun bigReply(payload: String): String = JsonObject().apply {
        addProperty("type", "api_response")
        addProperty("id", "r_abc_1")
        add("data", JsonObject().apply {
            addProperty("code", 200)
            addProperty("base64", payload)
        })
    }.toString()

    @Test
    fun smallReplyIsSentAsIs() {
        val text = bigReply("abc")
        assertEquals(listOf(text), InterconnectChunker.chunk("r_abc_1", text))
    }

    @Test
    fun base64GifSegmentSplitsUnderBudgetAndReassembles() {
        val payload = java.util.Base64.getEncoder().encodeToString(ByteArray(60_000) { (it * 31).toByte() })
        val text = bigReply(payload)
        val messages = InterconnectChunker.chunk("r_abc_1", text)
        assertTrue("应拆成多片: ${messages.size}", messages.size >= 7)
        for (m in messages) {
            val size = m.toByteArray(Charsets.UTF_8).size
            assertTrue("单片 $size 字节超预算", size <= InterconnectChunker.MESSAGE_BUDGET)
            assertEquals("chunk", JsonParser.parseString(m).asJsonObject.get("type").asString)
        }
        assertEquals(text, reassemble(messages))
    }

    @Test
    fun quotesChineseAndEmojiStayIntactAndUnderBudget() {
        val sb = StringBuilder()
        repeat(4000) { sb.append("歌名\"😀\\\n<a>") }
        val text = bigReply(sb.toString())
        val messages = InterconnectChunker.chunk("r_abc_1", text)
        for (m in messages) {
            assertTrue(m.toByteArray(Charsets.UTF_8).size <= InterconnectChunker.MESSAGE_BUDGET)
            val data = JsonParser.parseString(m).asJsonObject.get("data").asString
            assertTrue("代理对被劈开", !data.first().isLowSurrogate() && !data.last().isHighSurrogate())
        }
        assertEquals(text, reassemble(messages))
    }
}
