package com.hermes.agent.domain.model

/** Pure (no Android/Compose) so it's shared by the domain model, agent tools and the UI alike, and unit-tested. */
enum class AgentAvatarShape { ANTENNA, VISOR, HORNS, ROUND }

/** One selectable agent look: a small pixel-art robot bust in its own colours and silhouette. */
data class AgentAvatarDef(val id: String, val label: String, val primary: Int, val secondary: Int, val accent: Int, val shape: AgentAvatarShape)

object AgentAvatars {
    val PRESETS = listOf(
        AgentAvatarDef("sentinel", "Sentinel", 0xFF3B82F6.toInt(), 0xFF1E3A8A.toInt(), 0xFF93C5FD.toInt(), AgentAvatarShape.ANTENNA),
        AgentAvatarDef("scout", "Scout", 0xFF22C55E.toInt(), 0xFF14532D.toInt(), 0xFFBBF7D0.toInt(), AgentAvatarShape.VISOR),
        AgentAvatarDef("scribe", "Scribe", 0xFFF59E0B.toInt(), 0xFF78350F.toInt(), 0xFFFDE68A.toInt(), AgentAvatarShape.ROUND),
        AgentAvatarDef("ranger", "Ranger", 0xFF10B981.toInt(), 0xFF064E3B.toInt(), 0xFFD1FAE5.toInt(), AgentAvatarShape.HORNS),
        AgentAvatarDef("oracle", "Oracle", 0xFFA855F7.toInt(), 0xFF4C1D95.toInt(), 0xFFE9D5FF.toInt(), AgentAvatarShape.VISOR),
        AgentAvatarDef("nomad", "Nomad", 0xFFEF4444.toInt(), 0xFF7F1D1D.toInt(), 0xFFFECACA.toInt(), AgentAvatarShape.ANTENNA),
        AgentAvatarDef("cipher", "Cipher", 0xFF06B6D4.toInt(), 0xFF164E63.toInt(), 0xFFA5F3FC.toInt(), AgentAvatarShape.ROUND),
        AgentAvatarDef("warden", "Warden", 0xFF6366F1.toInt(), 0xFF312E81.toInt(), 0xFFC7D2FE.toInt(), AgentAvatarShape.HORNS)
    )
    const val DEFAULT_ID = "sentinel"

    /** Shown beside Hermes for a sub-agent with no saved profile (an ad-hoc delegate_task with no "agent" named). */
    val UNKNOWN = AgentAvatarDef("unknown", "Sub-agent", 0xFF6B7280.toInt(), 0xFF1F2937.toInt(), 0xFFD1D5DB.toInt(), AgentAvatarShape.ROUND)

    fun byId(id: String): AgentAvatarDef = PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()
}

/** Pixel data for [AgentAvatarDef], generated so frames are unit-tested without Android. */
object AgentAvatarSprite {
    const val WIDTH = 13
    const val HEIGHT = 14
    private const val OUTLINE = 0xFF14141F.toInt()

    private val BASE = listOf(
        "....KKKK.....",
        "...KBBBBK....",
        "..KBBBBBBK...",
        "..KBVVVVBK...",
        "..KBVEEVBK...",
        "..KBVVVVBK...",
        "..KBBBBBBK...",
        "...KBbbBK....",
        "..KKBBBBKK...",
        ".KBBBBBBBBK..",
        ".KbBBBBBBbK..",
        "..KK....KK..."
    )

    data class Pixel(val x: Int, val y: Int, val color: Int)

    private fun brighten(c: Int, amount: Int = 40): Int {
        val r = (((c shr 16) and 0xFF) + amount).coerceAtMost(255)
        val g = (((c shr 8) and 0xFF) + amount).coerceAtMost(255)
        val b = ((c and 0xFF) + amount).coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** [active] runs a brighter, faster cycle (shown beside Hermes while a sub-agent works); otherwise a slow idle bob. */
    fun pixels(def: AgentAvatarDef, frame: Int, active: Boolean): List<Pixel> {
        val out = mutableListOf<Pixel>()
        fun put(x: Int, y: Int, c: Int) { if (x in 0 until WIDTH && y in 0 until HEIGHT) out += Pixel(x, y, c) }

        val bob = if ((frame / 4) % 2 == 1) 1 else 0
        val blink = frame % 16 == 0
        val pulse = active && frame % 2 == 0
        val oy = 2 + bob
        BASE.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val c = when (ch) {
                    'K' -> OUTLINE
                    'B' -> def.primary
                    'b' -> def.secondary
                    'V' -> if (pulse) brighten(def.accent) else def.accent
                    'E' -> if (blink) def.accent else OUTLINE
                    else -> null
                }
                c?.let { put(x, oy + y, it) }
            }
        }
        val cx = WIDTH / 2
        when (def.shape) {
            AgentAvatarShape.ANTENNA -> { put(cx, 0, OUTLINE); put(cx, 1, def.secondary); put(cx, bob, def.accent) }
            AgentAvatarShape.HORNS -> {
                put(cx - 3, bob, def.secondary); put(cx - 3, 1, OUTLINE)
                put(cx + 2, bob, def.secondary); put(cx + 2, 1, OUTLINE)
            }
            AgentAvatarShape.VISOR -> (cx - 2..cx + 2).forEach { put(it, 1 + bob, def.secondary) }
            AgentAvatarShape.ROUND -> Unit
        }
        return out
    }
}
