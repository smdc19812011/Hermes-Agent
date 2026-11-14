package com.hermes.agent.ui.chat

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

/**
 * Pixel-art Hermes (winged helmet, red tunic) drawn cell by cell, with a small animated prop for each [AvatarState]:
 * thought dots, a typing bubble, a wrench, a book, a second little agent, sparkles, a magnifier, a plug, "?" and "!".
 */
@Composable
fun HermesAvatar(status: AvatarStatus, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Respect "remove animations": hold a still frame.
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val frame by produceState(1, status.state, still) {
        if (still) return@produceState
        while (true) { delay(if (status.state == AvatarState.IDLE) 220 else 140); value = value + 1 }
    }
    Canvas(modifier.semantics { contentDescription = "Hermes: ${status.text}" }) {
        val cell = minOf(size.width / AvatarSprite.WIDTH, size.height / AvatarSprite.HEIGHT)
        val left = (size.width - cell * AvatarSprite.WIDTH) / 2
        val top = (size.height - cell * AvatarSprite.HEIGHT) / 2
        AvatarSprite.pixels(status.state, frame).forEach { (x, y, c) ->
            // A hair of overlap hides seams between cells at fractional sizes.
            drawRect(Color(c), Offset(left + x * cell, top + y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** The sprite as data, so frames can be checked without Android. Colours are ARGB. */
object AvatarSprite {
    const val WIDTH = 30
    const val HEIGHT = 23

    private val PALETTE = mapOf(
        'K' to 0xFF1A1A2E, 'Y' to 0xFFF2B52B, 'y' to 0xFFC98A1A, 'O' to 0xFFFFD65A, 'W' to 0xFFF5F5F5, 'w' to 0xFFA9A9B8,
        'H' to 0xFF7A4A24, 'S' to 0xFFF2B98A, 's' to 0xFFD98F62, 'E' to 0xFF1A1A2E, 'R' to 0xFFC0392B, 'r' to 0xFF8E2A20,
        'G' to 0xFFFFD65A, 'P' to 0xFFFAF5E6, 'X' to 0xFFE6463C, 'C' to 0xFF78C8FF, 'B' to 0xFF5A96FA, 'L' to 0xFF8C8CA0
    ).mapValues { it.value.toInt() }

    private val WINGS_UP = listOf(
        "KK..................KK",
        "KWK................KWK",
        "KWWK....KKKKKK....KWWK",
        ".KwWK.KKYYYYYYKK.KWwK.",
        "..KwWKYOYYYYYYYYKWwK..",
        "...KwKYYYYYYYYYYKwK..."
    )
    private val WINGS_OUT = listOf(
        "......................",
        "......................",
        "........KKKKKK........",
        "KKK...KKYYYYYYKK...KKK",
        "KWWWWKYOYYYYYYYYKWWWWK",
        ".KKwwKYYYYYYYYYYKwwKK."
    )
    private val BODY = listOf(
        "....KyyyyyyyyyyyyK....",
        "...KHHHHHHHHHHHHHHK...",
        "...KHHSSSSSSSSSSHHK...",
        "...KHSSSSSSSSSSSSHK...",
        "...KHSSEESSSSEESSHK...",
        "....KSSEESSSSEESSK....",
        "....KsSSSSSSSSSSsK....",
        ".....KSSSSssSSSSK.....",
        "......KSSSSSSSSK......",
        ".......KKSSSSKK.......",
        ".....KKRRKSSKRRKK.....",
        "....KRRRRRGGRRRRRK....",
        "....KRrRRRRRRRRrRK...."
    )
    private const val EYE_TOP = 10   // rows within the 19-row head
    private const val MOUTH = 13

    private val ICONS = mapOf(
        "wrench" to listOf("..LL.", "..LwL", ".LwL.", "LwL..", "LL..."),
        "book" to listOf("KKKKKKK", "KPPKPPK", "KPPKPPK", "KPPKPPK", "KKKKKKK"),
        "agent" to listOf(".KKK.", "KYYYK", "KSSSK", "KEKEK", ".KKK."),
        "glass" to listOf(".KKK.", "KC.CK", "K.C.K", ".KKK.", "....K"),
        "plug" to listOf("O.O..", "O.O..", "OOOO.", ".OO..", "..O.."),
        "question" to listOf("BBB", "..B", ".B.", "...", ".B."),
        "bang" to listOf("X", "X", "X", ".", "X"),
        "bubble" to listOf("KKKKKKK", "KPPPPPK", "KPPPPPK", "KKKKKKK", ".K.....")
    )

    data class Pixel(val x: Int, val y: Int, val color: Int)

    fun pixels(state: AvatarState, frame: Int): List<Pixel> {
        val out = mutableListOf<Pixel>()
        fun put(x: Int, y: Int, c: Char) { if (x in 0 until WIDTH && y in 0 until HEIGHT) PALETTE[c]?.let { out += Pixel(x, y, it) } }
        fun icon(name: String, x0: Int, y0: Int) = ICONS.getValue(name).forEachIndexed { y, row -> row.forEachIndexed { x, c -> if (c != '.') put(x0 + x, y0 + y, c) } }

        val busy = state != AvatarState.IDLE && state != AvatarState.ERROR
        val flap = if (busy) frame % 2 == 1 else state == AvatarState.IDLE && frame % 16 in 5..6
        val bob = if ((frame / 4) % 2 == 1) 1 else 0
        val blink = frame % 24 == 0
        val ox = 1
        val oy = 3 + bob
        ((if (flap) WINGS_OUT else WINGS_UP) + BODY).forEachIndexed { y, row ->
            row.forEachIndexed { x, c0 ->
                var c = c0
                if (c == 'E') c = when {
                    blink -> if (y == EYE_TOP) 'S' else 'K'
                    state == AvatarState.THINKING && y == EYE_TOP + 1 -> 'S'          // looking up
                    state in setOf(AvatarState.READING, AvatarState.TOOL, AvatarState.CONNECTING) && x in setOf(7, 13) -> 'S' // looking at the prop
                    else -> c
                }
                if (c == 's' && y == MOUTH && state in setOf(AvatarState.TYPING, AvatarState.DELEGATING) && frame % 2 == 0) c = 'K' // talking
                if (c != '.') put(ox + x, oy + y, c)
            }
        }
        when (state) {
            AvatarState.THINKING -> listOf(23 to 8, 25 to 5, 26 to 2).forEachIndexed { i, (x, y) -> if (frame % 4 >= i) put(x, y, 'L') }
            AvatarState.TYPING -> { icon("bubble", 23, 1); repeat(3) { i -> if (frame % 4 > i) put(24 + i * 2, 2, 'K') } }
            AvatarState.TOOL -> icon("wrench", 24, frame % 2)
            AvatarState.SKILL -> icon("book", 23, if (frame % 2 == 1) 1 else 0)
            AvatarState.DELEGATING -> { icon("agent", 25, 0); repeat(2) { i -> if ((frame + i) % 2 == 1) put(23 + i, 3, 'O') } }
            AvatarState.GENERATING -> listOf(26 to 3, 23 to 1, 28 to 8, 1 to 1).forEachIndexed { i, (x, y) ->
                if ((frame + i) % 3 == 0) listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1).forEach { (dx, dy) -> put(x + dx, y + dy, 'O') }
            }
            AvatarState.READING -> icon("glass", 24, 1 + frame % 2)
            AvatarState.CONNECTING -> { icon("plug", 24, 1); if (frame % 2 == 1) put(26, 7, 'O') }
            AvatarState.WAITING -> icon("question", 26, frame % 2)
            AvatarState.ERROR -> { icon("bang", 27, 1); put(ox + 20, oy + 10, 'C'); put(ox + 20, oy + 11, 'C') }
            AvatarState.IDLE -> Unit
        }
        return out
    }
}
