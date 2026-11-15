package com.hermes.agent.ui.chat

import com.hermes.agent.domain.model.ToolActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarTest {
    private fun running(tool: String, label: String) = ToolActivity("id-$tool", tool, label, "", completed = false)
    private fun status(vararg tools: ToolActivity, loading: Boolean = true, approval: Boolean = false, sinceToken: Long = 10_000, sinceFail: Long = 60_000) =
        AvatarLogic.status(loading, tools.toList(), approval, sinceToken, sinceFail)

    @Test
    fun statusFollowsWhatTheReplyIsDoing() {
        assertEquals(AvatarStatus(AvatarState.IDLE, "Ready"), status(loading = false))
        assertEquals(AvatarState.ERROR, status(loading = false, sinceFail = 1_000).state)
        assertEquals(AvatarState.THINKING, status().state)
        assertEquals(AvatarState.TYPING, status(sinceToken = 300).state)
        assertEquals(AvatarState.WAITING, status(running("run_command", "ls"), approval = true).state)
        assertEquals(AvatarStatus(AvatarState.SKILL, "Using skill find-adb"), status(running("read_skill", "Reading skill find-adb")))
        assertEquals(AvatarStatus(AvatarState.GENERATING, "Generating an image"), status(running("generate_image", "a cat")))
        assertEquals(AvatarState.READING, status(running("read", "Reading photo.jpg")).state)
        assertEquals(AvatarStatus(AvatarState.CONNECTING, "GitHub: list_repos"), status(running("github__list_repos", "GitHub: list_repos")))
        assertEquals(AvatarStatus(AvatarState.TOOL, "Scanning the local network"), status(running("scan_lan", "Scanning the local network")))
        // A finished tool no longer counts.
        assertEquals(AvatarState.THINKING, status(running("scan_lan", "x").copy(completed = true)).state)
    }

    @Test
    fun delegationIsTheHeadlineWhileASubAgentWorks() {
        val s = status(running("delegate_task", "Sub-agent Network scout: map the LAN"), running("scan_lan", "↳ Network scout: Scanning"))
        assertEquals(AvatarStatus(AvatarState.DELEGATING, "Talking to Network scout", "Network scout"), s)
        val anonymous = status(running("delegate_task", "Sub-agent: do it"))
        assertEquals("Talking to a sub-agent", anonymous.text)
        assertEquals(null, anonymous.delegateAgentName)
    }

    @Test
    fun everyStateDrawsInsideTheCanvasAndAnimates() {
        for (state in AvatarState.entries) {
            val a = AvatarSprite.pixels(state, 1)
            val b = AvatarSprite.pixels(state, 2)
            assertTrue(a.all { it.x in 0 until AvatarSprite.WIDTH && it.y in 0 until AvatarSprite.HEIGHT })
            assertTrue("$state draws Hermes", a.size > 200)
            if (state != AvatarState.IDLE && state != AvatarState.ERROR) assertNotEquals("$state moves", a.toSet(), b.toSet())
        }
        assertNotEquals(AvatarSprite.pixels(AvatarState.TOOL, 1).toSet(), AvatarSprite.pixels(AvatarState.SKILL, 1).toSet())
    }
}
