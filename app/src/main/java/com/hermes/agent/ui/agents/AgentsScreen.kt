package com.hermes.agent.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.model.AgentAvatarDef
import com.hermes.agent.domain.model.AgentAvatars
import com.hermes.agent.domain.model.AgentProfile
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.tools.Capability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(onBack: () -> Unit, onOpenConnections: () -> Unit, vm: AgentsViewModel = hiltViewModel()) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val connectionNames = vm.connections.collectAsStateWithLifecycle().value.map { it.name }

    // null = closed; a blank item = new.
    var editSkill by remember { mutableStateOf<Skill?>(null) }
    var editAgent by remember { mutableStateOf<AgentProfile?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    editSkill?.let { s ->
        SkillEditor(s, connectionNames, error, onDismiss = { editSkill = null; vm.clearError() }) { saved ->
            vm.saveSkill(saved, s.name.ifBlank { null }) { editSkill = null }
        }
    }
    editAgent?.let { a ->
        AgentEditor(a, providers.filter { it.isEnabled }, vm.toolNames, connectionNames, error, onDismiss = { editAgent = null; vm.clearError() }) { saved ->
            vm.saveAgent(saved) { editAgent = null }
        }
    }
    confirmDelete?.let { (what, action) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete $what?") },
            text = { Text("No chat will be able to use it any more. This can't be undone.") },
            confirmButton = { TextButton(onClick = { action(); confirmDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills & agents") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Shared by every chat that uses a direct provider such as OpenRouter, while \"Skills & agents\" is on under Settings → Device access. " +
                    "Chats using the Hermes provider use Hermes' own skills and sub-agents instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionTitle("Skills", "Step-by-step instructions the AI loads when a task matches. It can save new ones after working something out, with your approval.")
            if (skills.isEmpty()) EmptyHint("No skills yet. Write one, or ask the AI to save what it just did as a skill.")
            skills.forEach { s ->
                LibraryRow(Icons.Default.MenuBook, s.name, s.description, onOpen = { editSkill = s },
                    onDelete = { confirmDelete = "skill ${s.name}" to { vm.deleteSkill(s.name) } })
            }
            OutlinedButton(onClick = { editSkill = Skill("", "", "") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New skill")
            }

            SectionTitle("Agents", "Saved specialists with their own instructions, models and tools. Start a chat with one from the new-chat button, " +
                "switch a chat to one from its top bar, or let the AI hand one a sub-task.")
            if (agents.isEmpty()) EmptyHint("No agents yet. Create one, or ask the AI to make one for a job.")
            agents.forEach { a ->
                val detail = listOf(
                    a.description,
                    a.models.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Models: ") ?: "Uses the chat's models",
                    if (a.createdByAi) "Made by the AI" else ""
                ).filter { it.isNotBlank() }.joinToString(" · ")
                LibraryRow(Icons.Default.Psychology, a.name, detail, avatar = AgentAvatars.byId(a.avatar), onOpen = { editAgent = a },
                    onDelete = { confirmDelete = "agent ${a.name}" to { vm.deleteAgent(a.id) } })
            }
            OutlinedButton(onClick = { editAgent = AgentProfile(name = "") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New agent")
            }

            SectionTitle("Connections", "MCP servers and APIs whose tools skills and agents can switch on.")
            OutlinedButton(onClick = onOpenConnections, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Extension, null); Spacer(Modifier.width(8.dp)); Text("Manage connections")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun LibraryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    avatar: AgentAvatarDef? = null
) {
    Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Edit $title", onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (avatar != null) {
                Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.CircleShape).padding(3.dp)) {
                    com.hermes.agent.ui.chat.AgentAvatarView(avatar, active = false, modifier = Modifier.fillMaxSize())
                }
            } else Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete $title") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScaffold(title: String, error: String?, canSave: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cancel") } },
                    actions = { TextButton(onClick = onSave, enabled = canSave) { Text("Save") } }
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                content()
            }
        }
    }
}

/** Chips for picking connections by name; names that no longer exist stay listed so they can be removed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionPicker(title: String, help: String, available: List<String>, selected: List<String>, onChange: (List<String>) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val all = (available + selected).distinctBy { it.lowercase() }
    if (all.isEmpty()) Text("No connections yet. Add them under Settings → Connections.", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        all.forEach { n ->
            val on = selected.any { it.equals(n, true) }
            FilterChip(selected = on, onClick = { onChange(if (on) selected.filterNot { it.equals(n, true) } else selected + n) },
                label = { Text(if (available.any { it.equals(n, true) }) n else "$n (missing)") })
        }
    }
}

@Composable
private fun SkillEditor(skill: Skill, connectionNames: List<String>, error: String?, onDismiss: () -> Unit, onSave: (Skill) -> Unit) {
    var name by remember { mutableStateOf(skill.name) }
    var connections by remember { mutableStateOf(skill.connections) }
    var description by remember { mutableStateOf(skill.description) }
    var body by remember { mutableStateOf(skill.body) }
    EditorScaffold(if (skill.name.isBlank()) "New skill" else "Edit skill", error, name.isNotBlank() && body.isNotBlank(), onDismiss,
        onSave = { onSave(Skill(name, description, body, connections)) }) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("find-adb-devices") }, singleLine = true,
            supportingText = { Text("Saved as lowercase words joined by hyphens") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text("When to use it") },
            placeholder = { Text("Finding Android devices with ADB open on the local network") },
            supportingText = { Text("The AI sees only this line until it opens the skill") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("Instructions (markdown)") }, minLines = 10, modifier = Modifier.fillMaxWidth())
        ConnectionPicker("Connections it uses", "Their tools switch on when the AI loads this skill, even if they're set to agents & skills only.",
            connectionNames, connections) { connections = it }
    }
}

/** A row of selectable little robot avatars, with the current pick shown larger alongside. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvatarPicker(selectedId: String, onSelect: (String) -> Unit) {
    Text("Avatar", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(6.dp)
        ) { com.hermes.agent.ui.chat.AgentAvatarView(AgentAvatars.byId(selectedId), active = true, modifier = Modifier.fillMaxSize()) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            AgentAvatars.PRESETS.forEach { def ->
                val selected = def.id == selectedId
                Box(
                    Modifier.size(40.dp)
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable(onClickLabel = def.label) { onSelect(def.id) }
                        .padding(5.dp)
                ) { com.hermes.agent.ui.chat.AgentAvatarView(def, active = false, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentEditor(
    agent: AgentProfile,
    providers: List<Provider>,
    toolNames: List<String>,
    connectionNames: List<String>,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (AgentProfile) -> Unit
) {
    var name by remember { mutableStateOf(agent.name) }
    var description by remember { mutableStateOf(agent.description) }
    var instructions by remember { mutableStateOf(agent.instructions) }
    var models by remember { mutableStateOf(agent.models.joinToString("\n")) }
    var limitAccess by remember { mutableStateOf(agent.deviceAccess != null) }
    var access by remember { mutableStateOf(agent.deviceAccess ?: Capability.entries.toSet()) }
    var tools by remember { mutableStateOf(agent.tools.joinToString(", ")) }
    var connections by remember { mutableStateOf(agent.connections) }
    var avatar by remember { mutableStateOf(agent.avatar) }
    var modelMenu by remember { mutableStateOf(false) }

    EditorScaffold(if (agent.name.isBlank()) "New agent" else "Edit agent", error, name.isNotBlank(), onDismiss, onSave = {
        onSave(agent.copy(
            name = name, description = description.trim(), instructions = instructions.trim(),
            models = models.lines().map { it.trim() }.filter { it.isNotEmpty() },
            deviceAccess = if (limitAccess) access else null,
            tools = tools.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
            connections = connections,
            avatar = avatar
        ))
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("Network scout") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        AvatarPicker(avatar, onSelect = { avatar = it })
        OutlinedTextField(description, { description = it }, label = { Text("What it's for") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, minLines = 6,
            placeholder = { Text("You map the user's network. Scan first, then check unusual hosts, and report a table of devices.") },
            modifier = Modifier.fillMaxWidth())

        OutlinedTextField(models, { models = it }, label = { Text("Models, tried in order") }, minLines = 2,
            supportingText = { Text("One per line, as model-id or \"Provider name: model-id\". Leave empty to use the chat's models.") },
            modifier = Modifier.fillMaxWidth())
        val choices = providers.filter { !it.isHermes }.flatMap { p -> p.models.filter { it.isNotBlank() }.map { "${p.name}: $it" } }
        if (choices.isNotEmpty()) Box {
            TextButton(onClick = { modelMenu = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add a model from your providers") }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                choices.forEach { c ->
                    DropdownMenuItem(text = { Text(c, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                        models = (models.lines().filter { it.isNotBlank() } + c).distinct().joinToString("\n"); modelMenu = false
                    })
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Limit device access", style = MaterialTheme.typography.titleSmall)
                Text("Off: everything Settings allows. It can never go beyond Settings.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = limitAccess, onCheckedChange = { limitAccess = it })
        }
        if (limitAccess) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Capability.entries.forEach { c ->
                FilterChip(selected = c in access, onClick = { access = if (c in access) access - c else access + c },
                    label = { Text("${c.emoji} ${c.displayName}") })
            }
        }

        OutlinedTextField(tools, { tools = it }, label = { Text("Allowed tools (optional)") },
            supportingText = { Text("Comma-separated, e.g. scan_lan, port_scan, run_command. Empty = every tool its device access enables.") },
            isError = tools.split(',', '\n').map { it.trim() }.any { it.isNotEmpty() && it !in toolNames && "__" !in it },
            modifier = Modifier.fillMaxWidth())
        ConnectionPicker("Connections", "This agent always gets these connections' tools.", connectionNames, connections) { connections = it }
    }
}
