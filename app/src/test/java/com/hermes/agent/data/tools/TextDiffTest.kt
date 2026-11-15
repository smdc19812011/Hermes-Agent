package com.hermes.agent.data.tools

import com.hermes.agent.data.tools.TextDiff.LineType
import org.junit.Assert.*
import org.junit.Test

class TextDiffTest {
    @Test
    fun marksUnchangedAddedAndRemovedLines() {
        val diff = TextDiff.diffLines(listOf("a", "b", "c"), listOf("a", "x", "c"))!!
        assertEquals(
            listOf(LineType.CONTEXT, LineType.REMOVE, LineType.ADD, LineType.CONTEXT),
            diff.map { it.type }
        )
        assertEquals(listOf("a", "b", "x", "c"), diff.map { it.text })
    }

    @Test
    fun handlesPureInsertionAndPureDeletion() {
        val inserted = TextDiff.diffLines(listOf("a", "c"), listOf("a", "b", "c"))!!
        assertEquals(listOf("a" to LineType.CONTEXT, "b" to LineType.ADD, "c" to LineType.CONTEXT), inserted.map { it.text to it.type })

        val deleted = TextDiff.diffLines(listOf("a", "b", "c"), listOf("a", "c"))!!
        assertEquals(listOf("a" to LineType.CONTEXT, "b" to LineType.REMOVE, "c" to LineType.CONTEXT), deleted.map { it.text to it.type })
    }

    @Test
    fun givesUpOnHugeInputsRatherThanFreezing() {
        val huge = (1..2000).map { "line$it" }
        assertNull(TextDiff.diffLines(huge, huge + "x")) // 2000 * 2001 > the 1,000,000-cell cap
        assertNotNull(TextDiff.diffLines(List(500) { "l$it" }, List(500) { "l$it" } + "y")) // comfortably under it
    }

    @Test
    fun renderPrefixesAddedAndRemovedLinesAndKeepsShortContextWhole() {
        val lines = listOf(
            TextDiff.DiffLine(LineType.CONTEXT, "same1"),
            TextDiff.DiffLine(LineType.REMOVE, "old"),
            TextDiff.DiffLine(LineType.ADD, "new"),
            TextDiff.DiffLine(LineType.CONTEXT, "same2")
        )
        assertEquals("  same1\n- old\n+ new\n  same2", TextDiff.render(lines))
    }

    @Test
    fun renderReportsNoChangeForAnAllContextDiff() {
        assertEquals("(no change)", TextDiff.render(listOf(TextDiff.DiffLine(LineType.CONTEXT, "a"))))
    }

    @Test
    fun renderTrimsLeadingTrailingAndInteriorContextRuns() {
        val lines = (1..10).map { TextDiff.DiffLine(LineType.CONTEXT, "ctx$it") } +
            TextDiff.DiffLine(LineType.ADD, "new") +
            (1..10).map { TextDiff.DiffLine(LineType.CONTEXT, "mid$it") } +
            TextDiff.DiffLine(LineType.REMOVE, "gone") +
            (1..10).map { TextDiff.DiffLine(LineType.CONTEXT, "end$it") }
        val rendered = TextDiff.render(lines, contextLines = 3)
        // Leading run: only the last 3 kept.
        assertTrue(rendered.startsWith("  ctx8\n  ctx9\n  ctx10\n+ new"))
        // Interior run: first 3 and last 3, with an elision marker, not all 10.
        assertTrue(rendered.contains("  mid1\n  mid2\n  mid3\n  ⋯\n  mid8\n  mid9\n  mid10\n- gone"))
        // Trailing run: only the first 3 kept.
        assertTrue(rendered.endsWith("- gone\n  end1\n  end2\n  end3"))
    }
}
