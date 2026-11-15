package com.hermes.agent.data.repository

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TextToolCallsTest {
    @Test
    fun parsesTheNemotronXmlFormatAndStripsItFromText() {
        val text = "Let me check the login page.\n<tool_call>\n<function=http_request>\n<parameter=url>https://hirex.inputs2insights.com/login</parameter>\n</function>\n</tool_call>"
        val (clean, calls) = TextToolCalls.parse(text)
        assertEquals("Let me check the login page.\n", clean)
        assertEquals(1, calls.size)
        assertEquals("http_request", calls[0].name)
        assertEquals("https://hirex.inputs2insights.com/login", JsonParser.parseString(calls[0].arguments).asJsonObject.get("url").asString)
    }

    @Test
    fun typesNumbersAndObjectsButKeepsUrlsAndTextAsStrings() {
        val text = "<function=port_scan><parameter=host>192.168.1.5</parameter><parameter=ports>[22,80]</parameter><parameter=timeout>600</parameter></function>"
        val args = JsonParser.parseString(TextToolCalls.parse(text).second.single().arguments).asJsonObject
        assertEquals("192.168.1.5", args.get("host").asString)
        assertTrue(args.get("ports").isJsonArray)
        assertEquals(600, args.get("timeout").asInt)
    }

    @Test
    fun handlesJsonInsideToolCallTags() {
        val text = """before <tool_call>{"name":"scan_lan","arguments":{"subnet":"10.0.0.0/24"}}</tool_call> after"""
        val (clean, calls) = TextToolCalls.parse(text)
        assertEquals("before  after", clean)
        assertEquals("scan_lan", calls.single().name)
        assertEquals("10.0.0.0/24", JsonParser.parseString(calls.single().arguments).asJsonObject.get("subnet").asString)
    }

    @Test
    fun leavesOrdinaryTextAndCodeAlone() {
        val text = "Compare a < b and use Map<String, Int>. No tools here."
        val (clean, calls) = TextToolCalls.parse(text)
        assertEquals(text, clean)
        assertTrue(calls.isEmpty())
        assertFalse(TextToolCalls.looksLikeCall(text))
    }

    @Test
    fun parsesSeveralCallsInOrder() {
        val text = "<function=a><parameter=x>1</parameter></function> and <function=b><parameter=y>two</parameter></function>"
        val calls = TextToolCalls.parse(text).second
        assertEquals(listOf("a", "b"), calls.map { it.name })
        assertNotEquals(calls[0].id, calls[1].id)
    }

    @Test
    fun filterHoldsBlocksAcrossChunkBoundariesSoTheyNeverStream() {
        val filter = TextToolCalls.Filter()
        val chunks = listOf("Checking ", "the site.<tool", "_call><function=http_", "request><parameter=url>", "https://x/login</parameter>", "</function></tool_call>", " Done.")
        val shown = buildString { chunks.forEach { append(filter.feed(it)) } }
        val (leftover, calls) = filter.finish()
        assertEquals("Checking the site. Done.", shown + leftover)
        assertEquals("http_request", calls.single().name)
        assertEquals("https://x/login", JsonParser.parseString(calls.single().arguments).asJsonObject.get("url").asString)
    }

    @Test
    fun filterReleasesALoneLessThanThatIsNotAMarker() {
        val filter = TextToolCalls.Filter()
        val shown = filter.feed("a < b") + filter.feed(" and c")
        val (leftover, calls) = filter.finish()
        assertEquals("a < b and c", shown + leftover)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun filterRecoversAnUnclosedBlockAtTheEnd() {
        val filter = TextToolCalls.Filter()
        val shown = filter.feed("ok <function=scan_lan><parameter=subnet>10.0.0.0/24</parameter>")
        val (leftover, calls) = filter.finish()
        assertEquals("ok ", shown + leftover)
        assertEquals("scan_lan", calls.single().name)
    }
}
