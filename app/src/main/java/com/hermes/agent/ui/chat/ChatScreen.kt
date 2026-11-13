package com.hermes.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    vm: ChatViewModel = hiltViewModel()
) {
    LaunchedEffect(conversationId) { vm.init(conversationId) }

    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var showRoutingMenu by remember { mutableStateOf(false) }

    val showStreamingItem = state.isLoading
    val itemCount = state.messages.size + if (showStreamingItem) 1 else 0
    LaunchedEffect(itemCount, state.streamingContent.length) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.conversation?.title ?: "Chat",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.activeProviderName?.let { name ->
                            Text(
                                "$name · ${state.activeModel ?: ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Unrestricted mode toggle
                    state.conversation?.let { conv ->
                        val unrestrictedIcon = when (conv.unrestrictedMode) {
                            UnrestrictedMode.OFF -> Icons.Default.Lock
                            UnrestrictedMode.NO_SYSTEM -> Icons.Default.LockOpen
                            UnrestrictedMode.CUSTOM -> Icons.Default.AllInclusive
                        }
                        val unrestrictedTint = when (conv.unrestrictedMode) {
                            UnrestrictedMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.primary
                        }
                        IconButton(onClick = { vm.cycleUnrestrictedMode() }) {
                            Icon(
                                unrestrictedIcon,
                                "Unrestricted mode: ${conv.unrestrictedMode.displayName}",
                                tint = unrestrictedTint
                            )
                        }
                    }

                    // Routing strategy menu
                    Box {
                        IconButton(onClick = { showRoutingMenu = true }) {
                            Icon(Icons.Default.AccountTree, "Routing")
                        }
                        DropdownMenu(
                            expanded = showRoutingMenu,
                            onDismissRequest = { showRoutingMenu = false }
                        ) {
                            RoutingStrategy.entries.forEach { strategy ->
                                val selected = state.conversation?.routingStrategy == strategy
                                DropdownMenuItem(
                                    text = { Text(strategy.displayName) },
                                    onClick = { vm.setRoutingStrategy(strategy); showRoutingMenu = false },
                                    leadingIcon = {
                                        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        else Spacer(Modifier.size(24.dp))
                                    }
                                )
                            }
                        }
                    }

                    IconButton(onClick = onOpenProviders) {
                        Icon(Icons.Default.Hub, "Providers")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(visible = state.notice != null) {
                    state.notice?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(err, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.clearNotice() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                ChatInput(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            vm.send(inputText)
                            inputText = ""
                        }
                    },
                    onStop = { vm.stop() },
                    isLoading = state.isLoading
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(state.messages, key = { it.id }) { msg ->
                MessageBubble(message = msg, showModel = state.showProviderBadge)
            }

            if (showStreamingItem) {
                item(key = "streaming") {
                    StreamingBubble(
                        content = state.streamingContent,
                        isLoading = state.isLoading && state.streamingContent.isEmpty()
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isLoading: Boolean
) {
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message…") },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            if (isLoading) {
                FilledTonalIconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, "Stop generating")
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, showModel: Boolean) {
    val isUser = message.role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("H", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                color = if (isUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            if (!isUser && showModel && message.model != null) {
                Text(
                    text = if (message.durationMs > 0) "${message.model} · ${message.durationMs / 1000.0}s" else message.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(content: String, isLoading: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("H", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(8.dp))

        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            if (isLoading) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(3) { idx ->
                        val delay = idx * 150
                        ThinkingDot(delayMs = delay)
                    }
                }
            } else {
                Text(
                    text = content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun ThinkingDot(delayMs: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = delayMs),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

