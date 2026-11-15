package com.hermes.agent.domain.model

import org.junit.Assert.*
import org.junit.Test

class AgentAvatarTest {
    @Test
    fun presetsAreDistinctAndTheDefaultResolves() {
        assertEquals(8, AgentAvatars.PRESETS.size)
        assertEquals(AgentAvatars.PRESETS.size, AgentAvatars.PRESETS.map { it.id }.distinct().size)
        assertEquals(AgentAvatars.DEFAULT_ID, AgentAvatars.byId(AgentAvatars.DEFAULT_ID).id)
        // An unknown saved id (e.g. from an older build) falls back instead of crashing.
        assertEquals(AgentAvatars.PRESETS.first(), AgentAvatars.byId("not-a-real-id"))
        assertTrue(AgentAvatars.UNKNOWN.id !in AgentAvatars.PRESETS.map { it.id })
    }

    @Test
    fun everyAvatarDrawsInsideTheCanvasAndAnimates() {
        for (def in AgentAvatars.PRESETS + AgentAvatars.UNKNOWN) {
            val idle = AgentAvatarSprite.pixels(def, 1, active = false)
            assertTrue(idle.all { it.x in 0 until AgentAvatarSprite.WIDTH && it.y in 0 until AgentAvatarSprite.HEIGHT })
            assertTrue("$def draws something", idle.size > 40)
            // Bobbing changes frame-to-frame even when idle.
            assertNotEquals("${def.id} moves", idle.toSet(), AgentAvatarSprite.pixels(def, 5, active = false).toSet())
        }
    }

    @Test
    fun shapesLookDifferentFromEachOtherInTheSamePalette() {
        val base = AgentAvatars.PRESETS.first()
        val variants = AgentAvatarShape.entries.associateWith { shape -> AgentAvatarSprite.pixels(base.copy(shape = shape), 1, active = false).toSet() }
        assertEquals(AgentAvatarShape.entries.size, variants.values.distinct().size)
    }

    @Test
    fun activePulsesTheVisorBrighterThanIdle() {
        val def = AgentAvatars.PRESETS.first()
        val idle = AgentAvatarSprite.pixels(def, 0, active = false).associate { (x, y, c) -> (x to y) to c }
        val active = AgentAvatarSprite.pixels(def, 0, active = true).associate { (x, y, c) -> (x to y) to c }
        assertNotEquals(idle, active)
    }
}
