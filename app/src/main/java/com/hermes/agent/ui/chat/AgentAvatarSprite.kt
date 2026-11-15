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
import com.hermes.agent.domain.model.AgentAvatarDef
import com.hermes.agent.domain.model.AgentAvatarSprite
import kotlinx.coroutines.delay

/** Draws an [AgentAvatarDef] from [AgentAvatarSprite]'s pixel data; see HermesAvatar for the same pattern. */
@Composable
fun AgentAvatarView(def: AgentAvatarDef, active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val frame by produceState(1, def.id, active, still) {
        if (still) return@produceState
        while (true) { delay(if (active) 140 else 220); value = value + 1 }
    }
    Canvas(modifier.semantics { contentDescription = "${def.label} avatar" }) {
        val cell = minOf(size.width / AgentAvatarSprite.WIDTH, size.height / AgentAvatarSprite.HEIGHT)
        val left = (size.width - cell * AgentAvatarSprite.WIDTH) / 2
        val top = (size.height - cell * AgentAvatarSprite.HEIGHT) / 2
        AgentAvatarSprite.pixels(def, frame, active).forEach { (x, y, c) ->
            drawRect(Color(c), Offset(left + x * cell, top + y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
