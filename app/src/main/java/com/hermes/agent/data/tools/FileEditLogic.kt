package com.hermes.agent.data.tools

/** The string-matching and diff-building behind [EditFileTool]/[WriteFileTool], kept free of Termux I/O so it's unit-tested. */
object FileEditLogic {
    data class EditResult(val newContent: String, val diff: String)

    /** How many non-overlapping times [needle] occurs in [haystack]. */
    fun occurrences(haystack: String, needle: String): Int = if (needle.isEmpty()) 0 else haystack.split(needle).size - 1

    /**
     * Replaces [oldString] with [newString] in [content] (once, or every occurrence with [replaceAll]) and renders
     * a diff of what changed. Throws with a message meant for the model to read and react to.
     */
    fun applyEdit(path: String, content: String, oldString: String, newString: String, replaceAll: Boolean): EditResult {
        require(oldString.isNotEmpty()) { "old_string can't be empty; use write_file to create a new file." }
        require(oldString != newString) { "old_string and new_string are identical; there's nothing to change." }
        val count = occurrences(content, oldString)
        when {
            count == 0 -> error("\"${oldString.take(80)}\" wasn't found in $path. Read the file again and copy the exact text, whitespace included.")
            count > 1 && !replaceAll -> error(
                "\"${oldString.take(80)}\" appears $count times in $path. Include a line or two more so it matches only one place, " +
                    "or pass replace_all to change every occurrence."
            )
        }
        val newContent = if (replaceAll) content.replace(oldString, newString) else content.replaceFirst(oldString, newString)
        val firstDiff = hunkAround(path, content, oldString, newString, content.indexOf(oldString))
        val diff = if (count > 1) "$firstDiff\n\n… and ${count - 1} more occurrence${if (count > 2) "s" else ""} replaced the same way." else firstDiff
        return EditResult(newContent, diff)
    }

    /** A unified-diff-style hunk for one occurrence of [oldString], with a few real lines of file context on each side. */
    private fun hunkAround(path: String, content: String, oldString: String, newString: String, matchIndex: Int, contextLines: Int = 3): String {
        val startLine = content.substring(0, matchIndex).count { it == '\n' } // 0-based
        val oldLines = oldString.split('\n')
        val newLines = newString.split('\n')
        val fileLines = content.split('\n')
        val endLine = startLine + oldLines.size - 1
        val before = fileLines.subList(maxOf(0, startLine - contextLines), startLine).map { TextDiff.DiffLine(TextDiff.LineType.CONTEXT, it) }
        val afterStart = minOf(fileLines.size, endLine + 1)
        val after = fileLines.subList(afterStart, minOf(fileLines.size, afterStart + contextLines)).map { TextDiff.DiffLine(TextDiff.LineType.CONTEXT, it) }
        val changed = TextDiff.diffLines(oldLines, newLines)
            ?: (oldLines.map { TextDiff.DiffLine(TextDiff.LineType.REMOVE, it) } + newLines.map { TextDiff.DiffLine(TextDiff.LineType.ADD, it) })
        return "$path, line${if (startLine == endLine) " ${startLine + 1}" else "s ${startLine + 1}-${endLine + 1}"}:\n" +
            TextDiff.render(before + changed + after, contextLines)
    }

    /** Full-file diff for [WriteFileTool]: every line added for a new file, or old vs new with a size-capped fallback. */
    fun diffWholeFile(path: String, oldContent: String?, newContent: String, contextLines: Int = 3): String {
        if (oldContent == null) {
            val added = newContent.split('\n')
            return "$path (new file, ${added.size} line${if (added.size != 1) "s" else ""}):\n" +
                TextDiff.render(added.map { TextDiff.DiffLine(TextDiff.LineType.ADD, it) }, contextLines)
        }
        if (oldContent == newContent) return "$path: no change."
        val oldLines = oldContent.split('\n')
        val newLines = newContent.split('\n')
        val diffed = TextDiff.diffLines(oldLines, newLines)
        return if (diffed != null) "$path:\n" + TextDiff.render(diffed, contextLines)
        else "$path: rewritten (too large to show line by line) — ${oldLines.size} line${if (oldLines.size != 1) "s" else ""} → " +
            "${newLines.size} line${if (newLines.size != 1) "s" else ""}."
    }
}
