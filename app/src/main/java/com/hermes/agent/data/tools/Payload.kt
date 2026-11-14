package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.prop
import com.hermes.agent.domain.tools.string
import java.util.Base64

/** Bytes the model sends or receives over a link, in the encoding it chose. */
object Payload {
    val dataProp = prop("string", "Data to send. Leave out to only receive.")
    val encodingProp = prop(
        "string",
        "How data is written: text (default; supports \\n \\r \\t \\xHH escapes), hex (\"01 a0 ff\"), or base64.",
        listOf("text", "hex", "base64")
    )

    fun from(args: JsonObject): ByteArray? {
        val data = args.get("data")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        if (data.isEmpty()) return null
        return decode(data, args.string("encoding") ?: "text")
    }

    fun decode(data: String, encoding: String): ByteArray = when (encoding.lowercase()) {
        "hex" -> {
            val clean = data.replace(Regex("(?i)0x|[\\s:,-]"), "")
            require(clean.length % 2 == 0 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Not valid hex: $data" }
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        "base64" -> Base64.getDecoder().decode(data.trim())
        else -> unescape(data).toByteArray(Charsets.UTF_8)
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    '0' -> { out.append('\u0000'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    'x' -> if (i + 3 < s.length && s.substring(i + 2, i + 4).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                        out.append(s.substring(i + 2, i + 4).toInt(16).toChar()); i += 4
                    } else { out.append(c); i++ }
                    else -> { out.append(c).append(n); i += 2 }
                }
            } else { out.append(c); i++ }
        }
        return out.toString()
    }

    /** Received bytes as text (when readable) plus hex, so the model can parse either. */
    fun describe(bytes: ByteArray, limit: Int = 8192): String {
        if (bytes.isEmpty()) return "0 bytes"
        val shown = bytes.copyOf(minOf(bytes.size, limit))
        val text = shown.toString(Charsets.UTF_8)
        val readable = text.count { it == '\n' || it == '\r' || it == '\t' || (it.code in 32..126) || it.code > 159 } >= text.length * 0.9
        val hex = shown.take(512).joinToString(" ") { "%02x".format(it) } + if (shown.size > 512) " …" else ""
        return buildString {
            append("${bytes.size} bytes")
            if (bytes.size > limit) append(" (first $limit shown)")
            if (readable) append("\ntext: ").append(text)
            append("\nhex: ").append(hex)
        }
    }
}
