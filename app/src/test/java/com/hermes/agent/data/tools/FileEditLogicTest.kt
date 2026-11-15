package com.hermes.agent.data.tools

import org.junit.Assert.*
import org.junit.Test

class FileEditLogicTest {
    @Test
    fun replacesASingleOccurrenceAndReportsWhereInTheFile() {
        val content = "line1\nline2\nTARGET\nline4\nline5"
        val result = FileEditLogic.applyEdit("x.py", content, "TARGET", "REPLACED", replaceAll = false)
        assertEquals("line1\nline2\nREPLACED\nline4\nline5", result.newContent)
        assertTrue(result.diff.contains("x.py, line 3:"))
        assertTrue(result.diff.contains("- TARGET"))
        assertTrue(result.diff.contains("+ REPLACED"))
        assertTrue(result.diff.contains("  line2")) // real surrounding context, not a diffed line
    }

    @Test
    fun refusesWhenOldStringIsMissingOrAmbiguous() {
        val missing = runCatching { FileEditLogic.applyEdit("x.py", "abc", "zzz", "q", false) }
        assertTrue(missing.exceptionOrNull()!!.message!!.contains("wasn't found"))

        val ambiguous = runCatching { FileEditLogic.applyEdit("x.py", "a\nb\na\nb", "b", "B", replaceAll = false) }
        assertTrue(ambiguous.exceptionOrNull()!!.message!!.contains("appears 2 times"))
    }

    @Test
    fun replaceAllChangesEveryOccurrenceAndSummarisesTheRest() {
        val content = "x=1\ny=x\nz=x"
        val result = FileEditLogic.applyEdit("x.py", content, "x", "n", replaceAll = true)
        assertEquals("n=1\ny=n\nz=n", result.newContent)
        assertTrue(result.diff.contains("and 2 more occurrences replaced the same way"))
    }

    @Test
    fun rejectsEmptyOrNoOpEdits() {
        assertTrue(runCatching { FileEditLogic.applyEdit("x", "a", "", "b", false) }.exceptionOrNull()!!.message!!.contains("old_string can't be empty"))
        assertTrue(runCatching { FileEditLogic.applyEdit("x", "a", "same", "same", false) }.exceptionOrNull()!!.message!!.contains("identical"))
    }

    @Test
    fun diffWholeFileCoversNewFilesNoChangeAndRewrites() {
        val newFile = FileEditLogic.diffWholeFile("new.txt", null, "a\nb")
        assertTrue(newFile.contains("new file, 2 lines"))
        assertTrue(newFile.contains("+ a"))
        assertTrue(newFile.contains("+ b"))

        assertEquals("same.txt: no change.", FileEditLogic.diffWholeFile("same.txt", "a\nb", "a\nb"))

        val changed = FileEditLogic.diffWholeFile("changed.txt", "a\nb", "a\nc")
        assertTrue(changed.contains("- b"))
        assertTrue(changed.contains("+ c"))
    }

    @Test
    fun diffWholeFileFallsBackToASummaryForHugeRewrites() {
        val old = (1..2000).joinToString("\n") { "old$it" }
        val new = (1..2000).joinToString("\n") { "new$it" }
        val diff = FileEditLogic.diffWholeFile("huge.txt", old, new)
        assertTrue(diff.contains("too large to show line by line"))
        assertTrue(diff.contains("2000 lines → 2000 lines"))
    }

    @Test
    fun occurrencesCountsNonOverlappingMatches() {
        assertEquals(2, FileEditLogic.occurrences("abcabc", "abc"))
        assertEquals(0, FileEditLogic.occurrences("abc", ""))
        assertEquals(0, FileEditLogic.occurrences("abc", "xyz"))
    }
}
