package com.hermes.agent.data.tools

/**
 * Finds web addresses the user typed, with or without a scheme (e.g. "hirex.example.com/admin/index.php"),
 * so the system prompt can tell the model to test the exact address given instead of guessing its own paths
 * (some models otherwise wander off to paths like /login that were never asked about).
 */
object TargetUrls {
    private val SCHEME_URL = Regex("""https?://[^\s<>"')]+""", RegexOption.IGNORE_CASE)

    // Domain or IPv4 host, with a mandatory path/query so a bare word like "Node.js" (no path) is never matched.
    private val BARE_HOST_PATH = Regex(
        """\b((?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,24}|(?:\d{1,3}\.){3}\d{1,3})(:\d{1,5})?(/[^\s<>"')]*)""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_PUNCTUATION = Regex("""[.,;:!?)\]}'"]+$""")

    /** Distinct addresses mentioned in [text], scheme URLs first, in the order they appear. */
    fun extractTargets(text: String): List<String> {
        val schemeMatches = SCHEME_URL.findAll(text).toList()
        // Mask scheme URLs so the bare-host pattern doesn't re-match the host/path portion of "https://host/path".
        val masked = buildString {
            var last = 0
            for (m in schemeMatches) {
                append(text, last, m.range.first)
                append(" ".repeat(m.value.length))
                last = m.range.last + 1
            }
            append(text, last, text.length)
        }
        val bareMatches = BARE_HOST_PATH.findAll(masked).map { it.value }
        return (schemeMatches.map { it.value } + bareMatches)
            .map { it.trim().let { v -> TRAILING_PUNCTUATION.replace(v, "") } }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** A system-prompt note telling the model to test these exact addresses first, or null if none were mentioned. */
    fun targetNote(userInput: String): String? {
        val targets = extractTargets(userInput)
        if (targets.isEmpty()) return null
        return "The user gave ${if (targets.size == 1) "this exact address" else "these exact addresses"}: ${targets.joinToString(", ")}. " +
            "Fetch ${if (targets.size == 1) "it" else "them"} exactly as given, path included, as your first step — don't substitute a path you " +
            "think is more likely (such as /login or /admin) instead of the one actually given. Only try other paths afterwards, and say plainly " +
            "when you do that you're guessing beyond what was asked."
    }
}
