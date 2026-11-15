package com.hermes.agent.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.domain.tools.ToolCall

/**
 * Some models (Nemotron, Hermes and other Llama-derived ones) write tool calls as text instead of using the API's
 * tool-calling field, e.g.
 *
 *   <tool_call><function=http_request><parameter=url>https://x/login</parameter></function></tool_call>
 *
 * or a JSON body inside <tool_call>…</tool_call>. Left as text these leak into the chat and the agent stalls.
 * [Filter] strips such blocks from the streamed text so they never show, and hands back real [ToolCall]s to run.
 */
object TextToolCalls {
    private val PARAM = Regex("<parameter=([^>]+)>(.*?)</parameter>", RegexOption.DOT_MATCHES_ALL)
    private val FUNC = Regex("<function=([^>\\s]+)\\s*>", RegexOption.DOT_MATCHES_ALL)
    // Longest first so a partial-tail check prefers the more specific marker.
    private val STARTS = listOf("<tool_call>", "<tool_call ", "<function=", "<function_call>", "<|tool_call|>")

    /** True if [text] contains a complete text tool-call block. */
    fun looksLikeCall(text: String): Boolean = parse(text).second.isNotEmpty()

    /** Returns the text with every complete tool-call block removed, and the calls found, in order. */
    fun parse(text: String, startIndex: Int = 0): Pair<String, List<ToolCall>> {
        val calls = mutableListOf<ToolCall>()
        val cleaned = StringBuilder()
        var i = 0
        var n = startIndex
        while (i < text.length) {
            val block = nextBlock(text, i)
            if (block == null) { cleaned.append(text, i, text.length); break }
            cleaned.append(text, i, block.first)
            toCall(text.substring(block.first, block.second), n)?.let { calls += it; n++ }
            i = block.second
        }
        return cleaned.toString() to calls
    }

    /** Start and end (exclusive) of the next complete block at or after [from], or null. */
    private fun nextBlock(text: String, from: Int): Pair<Int, Int>? {
        val wrap = indexOf(text, "<tool_call>", from) ?: indexOf(text, "<tool_call ", from)
        val func = FUNC.find(text, from)?.range?.first
        val start = listOfNotNull(wrap, func).minOrNull() ?: return null
        val end = when {
            start == wrap -> after(text, "</tool_call>", start)
            else -> after(text, "</function>", start)
        } ?: return null
        return start to end
    }

    private fun indexOf(text: String, needle: String, from: Int) = text.indexOf(needle, from).takeIf { it >= 0 }
    private fun after(text: String, needle: String, from: Int) = text.indexOf(needle, from).takeIf { it >= 0 }?.plus(needle.length)

    private fun toCall(block: String, n: Int): ToolCall? {
        val func = FUNC.find(block)
        if (func != null) {
            val name = func.groupValues[1].trim().ifEmpty { return null }
            val args = JsonObject()
            PARAM.findAll(block).forEach { m ->
                val key = m.groupValues[1].trim()
                val raw = m.groupValues[2].trim()
                args.add(key, jsonValue(raw))
            }
            return ToolCall("txt_$n", name, args.toString())
        }
        // <tool_call>{ "name": "...", "arguments": {...} }</tool_call>
        val inner = block.substringAfter('>', "").substringBeforeLast("</").trim()
        val json = runCatching { JsonParser.parseString(inner).asJsonObject }.getOrNull() ?: return null
        val name = (json.get("name") ?: json.get("tool"))?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: return null
        val arguments = json.get("arguments") ?: json.get("parameters")
        val argsText = when {
            arguments == null -> "{}"
            arguments.isJsonObject -> arguments.toString()
            arguments.isJsonPrimitive -> arguments.asString // some emit arguments as a JSON string
            else -> "{}"
        }
        return ToolCall("txt_$n", name, argsText.ifBlank { "{}" })
    }

    /** Keeps obvious JSON values (numbers, booleans, objects, arrays) typed; everything else stays a string. */
    private fun jsonValue(raw: String) = runCatching {
        val e = JsonParser.parseString(raw)
        if ((e.isJsonObject || e.isJsonArray || e.isJsonPrimitive) && (raw.startsWith("{") || raw.startsWith("[") ||
                raw == "true" || raw == "false" || raw.toDoubleOrNull() != null)) e
        else com.google.gson.JsonPrimitive(raw)
    }.getOrDefault(com.google.gson.JsonPrimitive(raw))

    /**
     * Streaming-safe scrubber: feed streamed text through it, emit what it returns, and call [finish] at the end.
     * It holds back text from a possible block start until the block completes (then drops it) or proves harmless.
     */
    class Filter {
        private val buf = StringBuilder()
        private val calls = mutableListOf<ToolCall>()
        private var counter = 0

        fun feed(text: String): String {
            buf.append(text)
            val out = StringBuilder()
            while (true) {
                val block = nextBlock(buf.toString(), 0)
                if (block != null) {
                    out.append(buf, 0, block.first)
                    toCall(buf.substring(block.first, block.second), counter)?.let { calls += it; counter++ }
                    buf.delete(0, block.second)
                    continue
                }
                // No complete block. Emit up to the earliest point that might begin one; hold the rest.
                val hold = earliestPossibleStart(buf.toString())
                out.append(buf, 0, hold)
                buf.delete(0, hold)
                break
            }
            return out.toString()
        }

        /** Leftover text and all calls found, best-effort parsing an unclosed block left at the very end. */
        fun finish(): Pair<String, List<ToolCall>> {
            var (text, more) = parse(buf.toString(), counter)
            val found = calls + more
            val all = found.toMutableList()
            // A model may stop before the closing tag; salvage a trailing start marker with parameters/JSON.
            val start = listOf("<tool_call>", "<tool_call ", "<function=", "<function_call>", "<|tool_call|>")
                .mapNotNull { text.indexOf(it).takeIf { i -> i >= 0 } }.minOrNull()
            if (start != null) {
                toCall(text.substring(start), all.size)?.let { all += it; text = text.substring(0, start) }
            }
            buf.clear(); calls.clear()
            return text to all
        }

        private fun earliestPossibleStart(s: String): Int {
            var cut = s.length
            for (marker in STARTS) {
                indexOf(s, marker, 0)?.let { cut = minOf(cut, it) }
                // A tail that could be the beginning of this marker.
                val maxK = minOf(marker.length - 1, s.length)
                for (k in maxK downTo 1) if (s.regionMatches(s.length - k, marker, 0, k)) { cut = minOf(cut, s.length - k); break }
            }
            return cut
        }
    }
}
