package com.hermes.agent.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentLearningLogicTest {
    @Test
    fun onlyAsksAfterATurnThatActuallyTouchedTheSystem() {
        assertTrue(EnvironmentLearningLogic.shouldAttempt(toolsRan = true, toolNamesUsed = setOf("run_command")))
        assertTrue(EnvironmentLearningLogic.shouldAttempt(toolsRan = true, toolNamesUsed = setOf("install_tool", "read_file")))
        assertFalse(EnvironmentLearningLogic.shouldAttempt(toolsRan = true, toolNamesUsed = setOf("read_file", "edit_file")))
        assertFalse(EnvironmentLearningLogic.shouldAttempt(toolsRan = false, toolNamesUsed = setOf("run_command")))
        assertFalse(EnvironmentLearningLogic.shouldAttempt(toolsRan = true, toolNamesUsed = emptySet()))
    }

    @Test
    fun treatsNoneAsNothingLearnedButKeepsAnythingElse() {
        assertNull(EnvironmentLearningLogic.parseLearning("NONE"))
        assertNull(EnvironmentLearningLogic.parseLearning("  none  "))
        assertNull(EnvironmentLearningLogic.parseLearning(""))
        assertNull(EnvironmentLearningLogic.parseLearning("   "))
        assertEquals("- aapt2 isn't on PATH for this ABI", EnvironmentLearningLogic.parseLearning("- aapt2 isn't on PATH for this ABI"))
    }

    @Test
    fun mergeDropsExactDuplicateLinesButKeepsDistinctOnes() {
        val result = EnvironmentLearningLogic.merge("- a known fact\n- another fact", "- a known fact\n- a new fact")
        assertEquals("- a known fact\n- another fact\n- a new fact", result)
    }

    @Test
    fun mergeDropsTheOldestLinesOnceOverTheCharBudget() {
        val existing = "- oldest line\n- middle line"
        val addition = "- newest line"
        val result = EnvironmentLearningLogic.merge(existing, addition, maxChars = "- middle line\n- newest line".length)
        assertEquals("- middle line\n- newest line", result)
    }

    @Test
    fun blankNotesContributeNothingToTheSystemPrompt() {
        assertNull(EnvironmentLearningLogic.systemPromptSection(""))
        assertNull(EnvironmentLearningLogic.systemPromptSection("   "))
        assertTrue(EnvironmentLearningLogic.systemPromptSection("- a fact")!!.contains("- a fact"))
    }
}
