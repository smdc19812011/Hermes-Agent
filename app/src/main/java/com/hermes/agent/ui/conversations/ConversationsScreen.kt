package com.hermes.agent.ui.conversations

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.model.Conversation
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenChat: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRuntime: () -> Unit,
    onOpenSandbox: () -> Unit,
    vm: ConversationsViewModel = hiltViewModel()
) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?") },
            text = { Text(target.title) },
            confirmButton = {
                TextButton(onClick = { vm.deleteConversation(target); deleteTarget = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Hermes", style = MaterialTheme.typography.headlineMedium)
                },
                actions = {
                    IconButton(onClick = onOpenRuntime) {
                        Icon(Icons.Default.SmartToy, contentDescription = "Agent runtime")
                    }
                    IconButton(onClick = onOpenSandbox) {
                        Icon(Icons.Default.Terminal, contentDescription = "Code sandbox")
                    }
                    IconButton(onClick = onOpenProviders) {
                        Icon(Icons.Default.Hub, contentDescription = "Providers")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            val agents by vm.agents.collectAsStateWithLifecycle()
            var agentMenu by remember { mutableStateOf(false) }
            Box {
                ExtendedFloatingActionButton(
                    // With saved agents, ask which one should run the new chat.
                    onClick = { if (agents.isEmpty()) vm.newConversation(onCreated = onOpenChat) else agentMenu = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New chat") }
                )
                DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                    DropdownMenuItem(text = { Text("Regular chat") }, leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                        onClick = { agentMenu = false; vm.newConversation(onCreated = onOpenChat) })
                    agents.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.name) },
                            leadingIcon = { Icon(Icons.Default.Psychology, null) },
                            onClick = { agentMenu = false; vm.newConversation(a.id, onOpenChat) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it; vm.setSearch(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search conversations…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = ""; vm.setSearch("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50)
            )

            if (conversations.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            onClick = { onOpenChat(conversation.id) },
                            onLongClick = { deleteTarget = conversation }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = {
            Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            if (conversation.lastMessage.isNotBlank()) {
                Text(
                    conversation.lastMessage,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDate(conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (conversation.unrestrictedMode.name != "OFF") {
                    Spacer(Modifier.height(4.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                        Text("∞", color = MaterialTheme.colorScheme.onTertiary)
                    }
                }
            }
        }
    )
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "No conversations yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Tap + to start a new chat",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000}m"
        diff < 86_400_000 -> "${diff / 3_600_000}h"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
