package com.hermes.agent.data.tools

/** A line-based diff engine with no Android dependency, so it's fully unit-tested. */
object TextDiff {
    enum class LineType { CONTEXT, ADD, REMOVE }
    data class DiffLine(val type: LineType, val text: String)

    /** DP table cells cap: keeps a full-file diff off the UI thread's budget on a phone. Past this, callers fall back to a summary. */
    private const val MAX_CELLS = 1_000_000L

    /** Null when [old]×[new] is too large to diff line-by-line. */
    fun diffLines(old: List<String>, new: List<String>): List<DiffLine>? {
        val n = old.size
        val m = new.size
        if (n.toLong() * m.toLong() > MAX_CELLS) return null
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (old[i] == new[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> { out += DiffLine(LineType.CONTEXT, old[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += DiffLine(LineType.REMOVE, old[i]); i++ }
                else -> { out += DiffLine(LineType.ADD, new[j]); j++ }
            }
        }
        while (i < n) { out += DiffLine(LineType.REMOVE, old[i]); i++ }
        while (j < m) { out += DiffLine(LineType.ADD, new[j]); j++ }
        return out
    }

    /** Renders [lines] with a leading marker per line ("+ ", "- ", "  "), collapsing long unchanged runs with "⋯". */
    fun render(lines: List<DiffLine>, contextLines: Int = 3): String {
        if (lines.none { it.type != LineType.CONTEXT }) return "(no change)"
        val sb = StringBuilder()
        var i = 0
        while (i < lines.size) {
            if (lines[i].type != LineType.CONTEXT) {
                sb.append(if (lines[i].type == LineType.ADD) "+ " else "- ").append(lines[i].text).append('\n')
                i++
                continue
            }
            var j = i
            while (j < lines.size && lines[j].type == LineType.CONTEXT) j++
            val run = lines.subList(i, j)
            val atStart = i == 0
            val atEnd = j == lines.size
            val shown = when {
                atStart && atEnd -> run
                atStart -> run.takeLast(contextLines)
                atEnd -> run.take(contextLines)
                run.size <= contextLines * 2 -> run
                else -> null
            }
            if (shown != null) {
                shown.forEach { sb.append("  ").append(it.text).append('\n') }
            } else {
                run.take(contextLines).forEach { sb.append("  ").append(it.text).append('\n') }
                sb.append("  ⋯\n")
                run.takeLast(contextLines).forEach { sb.append("  ").append(it.text).append('\n') }
            }
            i = j
        }
        return sb.toString().trimEnd('\n')
    }
}
