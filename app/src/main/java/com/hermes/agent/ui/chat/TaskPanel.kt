package com.hermes.agent.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.agent.domain.model.ToolActivity

/**
 * A right-side panel, like Claude's, showing the background tasks this chat has run — shell commands, HTTP calls,
 * connection and skill calls — with their live status and full output. It's a sibling of the chat, not an overlay:
 * the chat (prompt box included) narrows to make room while it's open. Drag its left edge to resize.
 */
@Composable
fun TaskPanelPane(tasks: List<ToolActivity>, open: Boolean, onClear: () -> Unit) {
    if (tasks.isEmpty()) return
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val density = LocalDensity.current
    val minWidth = 240.dp
    // Always leaves at least this much of the chat (and its prompt box) visible, even at max width.
    val maxWidth = (screenWidth - 120.dp).coerceAtLeast(minWidth)
    var openWidth by remember { mutableStateOf((screenWidth * 0.72f).coerceIn(minWidth, maxWidth)) }
    val width by animateDpAsState(if (open) openWidth else 0.dp, label = "taskPanelWidth")

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp, shadowElevation = if (open) 12.dp else 0.dp,
        modifier = Modifier.width(width).fillMaxHeight().clipToBounds()
    ) {
        if (width < 24.dp) return@Surface // nothing useful fits; skip laying out the content while closed
        Row(Modifier.fillMaxSize()) {
            // Drag handle: resizes the panel; doesn't carry the hide control any more (see TaskPanelHandle).
            Box(
                Modifier.fillMaxHeight().width(16.dp).background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(maxWidth) {
                        detectHorizontalDragGestures { _, drag ->
                            openWidth = (openWidth - with(density) { drag.toDp() }).coerceIn(minWidth, maxWidth)
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.DragHandle, "Resize", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }

            Column(Modifier.fillMaxHeight().weight(1f)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Tasks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks.asReversed(), key = { it.id }) { TaskRow(it) }
                }
            }
        }
    }
}

/**
 * The open/close control for [TaskPanelPane], pinned to the vertical middle of the screen's right edge — which is
 * also the task window's own edge once it's open, so this doubles as the panel's "hide" button in place.
 */
@Composable
fun BoxScope.TaskPanelHandle(open: Boolean, count: Int, anyRunning: Boolean, onToggle: () -> Unit) {
    if (count == 0) return
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp, shadowElevation = 6.dp,
        modifier = Modifier.align(Alignment.CenterEnd)
    ) {
        if (open) {
            Box(Modifier.padding(vertical = 14.dp, horizontal = 6.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Hide tasks", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        } else {
            Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (anyRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                else Icon(Icons.Default.Terminal, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Tasks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun TaskRow(task: ToolActivity) {
    var expanded by remember(task.id) { mutableStateOf(!task.completed) }
    val border = when {
        task.failed -> MaterialTheme.colorScheme.error
        !task.completed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().border(1.dp, border, RoundedCornerShape(10.dp))
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !task.completed -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    task.failed -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(task.display, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                if (task.output.isNotEmpty()) Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
            }
            if (expanded && task.output.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            task.output,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
