package com.hermes.agent.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.repository.ThrottleLogic
import com.hermes.agent.data.sandbox.SandboxLanguage
import com.hermes.agent.domain.model.*
import com.hermes.agent.ui.sandbox.ResultPanel

private val CODE_FENCE = Regex("```([A-Za-z0-9_+-]*)[^\\n]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

private data class RunnableBlock(val language: SandboxLanguage, val code: String)

private fun runnableBlocks(content: String, termuxReady: Boolean): List<RunnableBlock> =
    CODE_FENCE.findAll(content).mapNotNull { m ->
        SandboxLanguage.fromFence(m.groupValues[1], termuxReady)?.let { RunnableBlock(it, m.groupValues[2].trimEnd()) }
    }.toList()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAgents: () -> Unit,
    vm: ChatViewModel = hiltViewModel()
) {
    LaunchedEffect(conversationId) { vm.init(conversationId) }
    LifecycleResumeEffect(Unit) {
        vm.refreshTermux()
        onPauseOrDispose { }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val attachmentActions = remember(vm) { AttachmentActions(vm::fileOf, vm::saveAttachment, vm::shareAttachment) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.attach(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.attach(it) }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        cameraFile?.let { vm.attachPhoto(it, taken) }
        cameraFile = null
    }
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var showRoutingMenu by remember { mutableStateOf(false) }
    var showAgentMenu by remember { mutableStateOf(false) }
    val agents by vm.agents.collectAsStateWithLifecycle()
    val activeAgent = agents.firstOrNull { it.id == state.conversation?.agentId }

    val showStreamingItem = state.isLoading
    val itemCount = state.messages.size + if (showStreamingItem) 1 else 0
    LaunchedEffect(itemCount, state.streamingContent.length, state.tools.size) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
    }

    state.codeRun?.let { run ->
        ModalBottomSheet(onDismissRequest = { vm.dismissCodeRun() }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Run ${run.language.displayName}", style = MaterialTheme.typography.titleMedium)
                CodeBlock(run.code)
                val result = run.result
                if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else ResultPanel(result)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.conversation?.title ?: "Chat", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val agentLabel = activeAgent?.let { "${it.name} · " }.orEmpty()
                        if (state.activeProviderName == null && activeAgent != null) {
                            Text(activeAgent.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        }
                        state.activeProviderName?.let { name ->
                            Text(
                                "$agentLabel$name · ${state.activeModel.orEmpty()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    state.conversation?.let { conv ->
                        val icon = when (conv.unrestrictedMode) {
                            UnrestrictedMode.OFF -> Icons.Default.Lock
                            UnrestrictedMode.NO_SYSTEM -> Icons.Default.LockOpen
                            UnrestrictedMode.CUSTOM -> Icons.Default.AllInclusive
                        }
                        val tint = if (conv.unrestrictedMode == UnrestrictedMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                        IconButton(onClick = { vm.cycleUnrestrictedMode() }) {
                            Icon(icon, "Unrestricted mode: ${conv.unrestrictedMode.displayName}", tint = tint)
                        }
                    }
                    if (state.panelTasks.isNotEmpty()) IconButton(onClick = { vm.setPanelOpen(!state.panelOpen) }) {
                        BadgedBox(badge = { Badge { Text("${state.panelTasks.size}") } }) { Icon(Icons.Default.Terminal, "Tasks") }
                    }
                    Box {
                        IconButton(onClick = { showAgentMenu = true }) {
                            Icon(Icons.Default.Psychology, activeAgent?.let { "Agent: ${it.name}" } ?: "Choose an agent",
                                tint = if (activeAgent != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showAgentMenu, onDismissRequest = { showAgentMenu = false }) {
                            (listOf<com.hermes.agent.domain.model.AgentProfile?>(null) + agents).forEach { a ->
                                DropdownMenuItem(
                                    text = { Text(a?.name ?: "No agent") },
                                    onClick = { vm.setAgent(a?.id); showAgentMenu = false },
                                    leadingIcon = {
                                        if (activeAgent?.id == a?.id) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        else Spacer(Modifier.size(24.dp))
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Manage skills & agents") }, onClick = { showAgentMenu = false; onOpenAgents() },
                                leadingIcon = { Icon(Icons.Default.Settings, null) })
                        }
                    }
                    Box {
                        IconButton(onClick = { showRoutingMenu = true }) { Icon(Icons.Default.AccountTree, "Routing") }
                        DropdownMenu(expanded = showRoutingMenu, onDismissRequest = { showRoutingMenu = false }) {
                            RoutingStrategy.entries.forEach { strategy ->
                                DropdownMenuItem(
                                    text = { Text(strategy.displayName) },
                                    onClick = { vm.setRoutingStrategy(strategy); showRoutingMenu = false },
                                    leadingIcon = {
                                        if (state.conversation?.routingStrategy == strategy) {
                                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        } else Spacer(Modifier.size(24.dp))
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenProviders) { Icon(Icons.Default.Hub, "Providers") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Column {
                state.approval?.let { ApprovalCard(it, onAnswer = vm::answerApproval) }
                AnimatedVisibility(visible = state.notice != null) {
                    state.notice?.let { NoticeBar(it, onDismiss = vm::clearNotice) }
                }
                // Ticks while something is happening, so the avatar can tell typing from thinking and let an error fade.
                val now by produceState(System.currentTimeMillis(), state.isLoading, state.failedAt) {
                    while (state.isLoading || System.currentTimeMillis() - state.failedAt < 6_000) {
                        value = System.currentTimeMillis(); kotlinx.coroutines.delay(400)
                    }
                    value = System.currentTimeMillis()
                }
                val avatarStatus = AvatarLogic.status(
                    state.isLoading, state.tools, state.approval != null, now - state.lastTokenAt, now - state.failedAt,
                    msUntilResume = (state.waitingUntil - now).coerceAtLeast(0), resumeLabel = state.waitingLabel
                )
                // Whoever delegate_task is talking to right now, shown beside Hermes: the saved agent's own look, or a neutral stand-in.
                val delegateAvatar = when {
                    avatarStatus.delegateAgentName == null -> null
                    else -> agents.firstOrNull { it.name.equals(avatarStatus.delegateAgentName, ignoreCase = true) }
                        ?.let { AgentAvatars.byId(it.avatar) } ?: AgentAvatars.UNKNOWN
                }
                // The "today" figure comes from OpenRouter's own observability (free_model_daily_requests) when it's
                // reporting one, so it reflects OpenRouter's own count rather than just what this app has seen locally;
                // otherwise it falls back to this app's own local count. The window figure stays this app's own local
                // pacing window, since OpenRouter's "rate_limit" field is deprecated and often unavailable.
                val requestStatsText = state.activeProviderId?.let { id ->
                    val usage = state.openRouterUsage
                    val windowMs = ThrottleLogic.parseIntervalMs(usage?.rateLimitInterval) ?: 60_000L
                    val windowLabel = usage?.rateLimitInterval ?: "min"
                    val counts = vm.requestStats(id, windowMs)
                    val today = usage?.freeModelDailyUsed ?: counts.today
                    ThrottleLogic.formatRequestStats(today, usage?.freeModelDailyLimit, counts.lastMinute, windowLabel, usage?.rateLimitRequests)
                }
                ChatInput(
                    avatar = avatarStatus,
                    delegateAvatar = delegateAvatar,
                    requestStatsText = requestStatsText,
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank() || state.pending.isNotEmpty()) {
                            vm.send(inputText)
                            inputText = ""
                        }
                    },
                    onStop = vm::stop,
                    isLoading = state.isLoading,
                    pending = state.pending,
                    importing = state.importing,
                    onRemovePending = vm::removePending,
                    onPickMedia = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onTakePhoto = {
                        val (file, uri) = vm.cameraTarget()
                        cameraFile = file
                        camera.launch(uri)
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.messages, key = { it.id }) { msg ->
                MessageBubble(
                    message = msg,
                    showModel = state.showProviderBadge,
                    runnable = if (msg.role == Role.ASSISTANT) runnableBlocks(msg.content, state.termuxReady) else emptyList(),
                    onRun = { vm.runCode(it.language, it.code) },
                    attachmentActions = attachmentActions
                )
            }
            if (showStreamingItem) {
                item(key = "streaming") {
                    StreamingBubble(content = state.streamingContent, tools = state.tools, files = state.streamingFiles, attachmentActions = attachmentActions)
                }
            }
        }
    }
    }
        TaskPanelPane(tasks = state.panelTasks, open = state.panelOpen, onClear = { vm.clearPanel() })
    }
        TaskPanelHandle(
            open = state.panelOpen,
            count = state.panelTasks.size,
            anyRunning = state.isLoading && state.panelTasks.any { !it.completed },
            onToggle = { vm.setPanelOpen(!state.panelOpen) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalCard(pending: PendingApproval, onAnswer: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(if (pending.request.local) "Allow this command?" else "Hermes wants to run a command", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Column(
                Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (pending.request.command.isNotBlank()) CodeBlock(pending.request.command)
                if (pending.request.description.isNotBlank()) {
                    Text(pending.request.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pending.request.choices.forEach { choice ->
                    val label = when (choice) {
                        "once" -> "Allow once"
                        "session" -> "Allow in this chat"
                        "always" -> "Always allow"
                        "deny" -> "Deny"
                        else -> choice
                    }
                    if (choice == "deny") {
                        OutlinedButton(enabled = !pending.sending, onClick = { onAnswer(choice) }) { Text(label) }
                    } else {
                        Button(enabled = !pending.sending, onClick = { onAnswer(choice) }) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ChatInput(
    avatar: AvatarStatus,
    delegateAvatar: AgentAvatarDef?,
    requestStatsText: String?,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isLoading: Boolean,
    pending: List<Attachment>,
    importing: Boolean,
    onRemovePending: (Attachment) -> Unit,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(width = 45.dp, height = 35.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(2.dp)
                ) { HermesAvatar(avatar, Modifier.fillMaxSize()) }
                AnimatedVisibility(visible = delegateAvatar != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(14.dp).padding(horizontal = 2.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(
                            Modifier.size(width = 34.dp, height = 35.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                .padding(3.dp)
                        ) { delegateAvatar?.let { AgentAvatarView(it, active = true, modifier = Modifier.fillMaxSize()) } }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    avatar.text,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (avatar.state) {
                        AvatarState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                        AvatarState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
                )
                requestStatsText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (pending.isNotEmpty() || importing) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pending.forEach { a -> PendingChip(a, onRemove = { onRemovePending(a) }) }
                    if (importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box {
                    IconButton(onClick = { menu = true }, enabled = !isLoading) { Icon(Icons.Default.Add, "Attach") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Photo or video") }, leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                            onClick = { menu = false; onPickMedia() })
                        DropdownMenuItem(text = { Text("Take photo") }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                            onClick = { menu = false; onTakePhoto() })
                        DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                            onClick = { menu = false; onPickFile() })
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (pending.isEmpty()) "Message…" else "Add a message (optional)") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                if (isLoading) {
                    FilledTonalIconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop generating") }
                } else {
                    FilledIconButton(onClick = onSend, enabled = !importing && (value.isNotBlank() || pending.isNotEmpty())) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingChip(a: Attachment, onRemove: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            if (a.kind == Attachment.Kind.IMAGE || a.kind == Attachment.Kind.VIDEO) {
                coil.compose.AsyncImage(
                    model = java.io.File(context.filesDir, a.uri), contentDescription = a.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(64.dp)
                )
            } else {
                Column(Modifier.size(width = 120.dp, height = 64.dp).padding(8.dp), verticalArrangement = Arrangement.Center) {
                    Icon(if (a.kind == Attachment.Kind.AUDIO) Icons.Default.AudioFile else Icons.Default.Description, null, Modifier.size(18.dp))
                    Text(a.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.inverseSurface).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Close, "Remove ${a.name}", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.inverseOnSurface) }
    }
}

@Composable
private fun Avatar() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text("H", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    showModel: Boolean,
    runnable: List<RunnableBlock>,
    onRun: (RunnableBlock) -> Unit,
    attachmentActions: AttachmentActions
) {
    val isUser = message.role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Avatar()
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (message.toolLog.isNotEmpty()) ToolLog(message.toolLog)
            if (message.content.isNotBlank() || message.attachments.isEmpty()) Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            if (message.attachments.isNotEmpty()) AttachmentList(message.attachments, attachmentActions)
            if (runnable.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    runnable.forEachIndexed { i, block ->
                        AssistChip(
                            onClick = { onRun(block) },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)) },
                            label = { Text(if (runnable.size > 1) "Run ${block.language.displayName} #${i + 1}" else "Run ${block.language.displayName}") }
                        )
                    }
                }
            }
            if (!isUser && showModel && message.model != null) {
                Text(
                    text = if (message.durationMs > 0) "${message.model} · ${"%.1f".format(message.durationMs / 1000.0)}s" else message.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolLog(entries: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Build, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(6.dp))
            Text(
                "Used ${entries.size} tool${if (entries.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp))
        }
        if (expanded) {
            entries.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StreamingBubble(content: String, tools: List<ToolActivity>, files: List<Attachment>, attachmentActions: AttachmentActions) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Avatar()
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tools.forEach { tool ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tool.completed) {
                        Icon(Icons.Default.CheckCircle, "Done", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tool.display,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (files.isNotEmpty()) AttachmentList(files, attachmentActions)
            Surface(
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                if (content.isEmpty()) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) { ThinkingDot(delayMs = it * 150) }
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
}

@Composable
private fun CodeBlock(code: String) {
    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(8.dp)) {
        SelectionContainer {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)
            )
        }
    }
}

@Composable
private fun ThinkingDot(delayMs: Int) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = delayMs), repeatMode = RepeatMode.Reverse),
        label = "dot_alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}
