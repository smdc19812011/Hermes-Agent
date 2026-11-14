package com.hermes.agent.ui.connections

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.connections.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(onBack: () -> Unit, vm: ConnectionsViewModel = hiltViewModel()) {
    val connections by vm.connections.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Connection?>(null) }
    var deleting by remember { mutableStateOf<Connection?>(null) }
    val openBrowser = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    editing?.let { c ->
        ConnectionEditor(c, error, onDismiss = { editing = null; vm.clearError() }) { saved -> vm.save(saved) { editing = null } }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("Its tools disappear from every chat, agent and skill.") },
            confirmButton = { TextButton(onClick = { vm.delete(c.id); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
    if (editing == null) error?.let { e ->
        AlertDialog(onDismissRequest = vm::clearError, title = { Text("Connection problem") }, text = { Text(e) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = Connection(name = "") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add connection") })
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Connect MCP servers and web APIs to give the AI more tools. Each connection's tools reach every chat, or only the agents and skills " +
                    "that list it. Turn on \"Connections\" under Settings → Device access to use them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (connections.isEmpty()) Text("No connections yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            connections.forEach { c ->
                ConnectionCard(
                    c, c.id in busy,
                    onEdit = { editing = c }, onDelete = { deleting = c },
                    onRefresh = { vm.refresh(c.id) }, onSignIn = { vm.signIn(c.id, openBrowser) }, onSignOut = { vm.signOut(c.id) },
                    onEnabled = { vm.setEnabled(c, it) }, onToolEnabled = { tool, on -> vm.setToolEnabled(c.id, tool, on) }
                )
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun ConnectionCard(
    c: Connection, busy: Boolean,
    onEdit: () -> Unit, onDelete: () -> Unit, onRefresh: () -> Unit, onSignIn: () -> Unit, onSignOut: () -> Unit,
    onEnabled: (Boolean) -> Unit, onToolEnabled: (String, Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val status = when {
        busy -> "Working…"
        c.lastError.isNotBlank() -> c.lastError
        c.tools.isEmpty() -> "No tools loaded yet"
        else -> "${c.activeTools.size} of ${c.tools.size} tools on · ${c.scope.displayName}"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClickLabel = if (expanded) "Hide tools" else "Show tools") { expanded = !expanded }) {
                Icon(if (c.type == ConnectionType.REST) Icons.Default.Api else Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    Text(c.type.displayName + if (c.auth == AuthType.OAUTH) (if (c.oauth.signedIn) " · signed in" else " · not signed in") else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = if (c.lastError.isNotBlank() && !busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = c.enabled, onCheckedChange = onEnabled)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (c.auth == AuthType.OAUTH) {
                    if (c.oauth.signedIn) TextButton(onClick = onSignOut, enabled = !busy) { Text("Sign out") }
                    else FilledTonalButton(onClick = onSignIn, enabled = !busy) { Text("Sign in") }
                }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Load tools") }
                TextButton(onClick = onEdit) { Text("Edit") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete ${c.name}") }
            }
            AnimatedVisibility(expanded && c.tools.isNotEmpty()) {
                Column {
                    HorizontalDivider()
                    c.tools.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name + if (t.readOnly) "  · read-only" else "", style = MaterialTheme.typography.bodyMedium)
                                Text(t.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = t.name !in c.disabledTools, onCheckedChange = { onToolEnabled(t.name, it) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConnectionEditor(c: Connection, error: String?, onDismiss: () -> Unit, onSave: (Connection) -> Unit) {
    var d by remember { mutableStateOf(c) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(if (c.name.isBlank()) "Add connection" else "Edit connection") },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cancel") } },
                actions = { TextButton(onClick = { onSave(d) }, enabled = d.name.isNotBlank()) { Text("Save") } }
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(d.name, { d = d.copy(name = it) }, label = { Text("Name") }, singleLine = true,
                    placeholder = { Text("GitHub, Notion, Home Assistant…") }, modifier = Modifier.fillMaxWidth())

                Text("Type", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConnectionType.entries.forEach { t -> FilterChip(selected = d.type == t, onClick = { d = d.copy(type = t) }, label = { Text(t.displayName) }) }
                }
                Text(d.type.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.type == ConnectionType.MCP_LOCAL) {
                    OutlinedTextField(d.command, { d = d.copy(command = it) }, label = { Text("Command") }, minLines = 2,
                        placeholder = { Text("npx -y @modelcontextprotocol/server-filesystem ~/storage/shared") }, modifier = Modifier.fillMaxWidth())
                    Text("Needs Termux set up (Agent runtime screen). Node.js is installed if missing, and the first start can take a minute while packages download.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(d.url, { d = d.copy(url = it.trim()) }, label = { Text(if (d.type == ConnectionType.REST) "API base URL" else "Server URL") },
                        singleLine = true, placeholder = { Text(if (d.type == ConnectionType.REST) "https://api.example.com/v1" else "https://example.com/mcp") },
                        modifier = Modifier.fillMaxWidth())
                }
                if (d.type == ConnectionType.REST) {
                    OutlinedTextField(d.openApiUrl, { d = d.copy(openApiUrl = it.trim()) }, label = { Text("OpenAPI document (optional, JSON)") }, singleLine = true,
                        placeholder = { Text("https://api.example.com/openapi.json") }, modifier = Modifier.fillMaxWidth())
                }

                Text("Authentication", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthType.entries.forEach { a -> FilterChip(selected = d.auth == a, onClick = { d = d.copy(auth = a) }, label = { Text(a.displayName) }) }
                }
                when (d.auth) {
                    AuthType.NONE -> Unit
                    AuthType.BEARER -> Secret("Token or API key", d.secret) { d = d.copy(secret = it) }
                    AuthType.HEADER -> {
                        OutlinedTextField(d.authName, { d = d.copy(authName = it.trim()) }, label = { Text("Header name") }, placeholder = { Text("X-Api-Key") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Secret("Header value", d.secret) { d = d.copy(secret = it) }
                    }
                    AuthType.BASIC -> {
                        OutlinedTextField(d.authName, { d = d.copy(authName = it) }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Secret("Password", d.secret) { d = d.copy(secret = it) }
                    }
                    AuthType.OAUTH -> {
                        Text(
                            if (d.type == ConnectionType.REST) "Register an app with the service using the redirect URI below, then enter its details."
                            else "Leave these empty for MCP servers that support automatic sign-in; fill them in if the server asks for a client ID.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Redirect URI: ${OAuth.REDIRECT_URI}", style = MaterialTheme.typography.bodySmall)
                        val o = d.oauth
                        OutlinedTextField(o.clientId, { d = d.copy(oauth = o.copy(clientId = it.trim())) }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Secret("Client secret (if required)", o.clientSecret) { d = d.copy(oauth = o.copy(clientSecret = it.trim())) }
                        OutlinedTextField(o.authUrl, { d = d.copy(oauth = o.copy(authUrl = it.trim())) }, label = { Text("Authorization URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(o.tokenUrl, { d = d.copy(oauth = o.copy(tokenUrl = it.trim())) }, label = { Text("Token URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(o.scopes, { d = d.copy(oauth = o.copy(scopes = it)) }, label = { Text("Scopes (space-separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }

                Text("Which chats get its tools", style = MaterialTheme.typography.titleSmall)
                ToolScope.entries.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { d = d.copy(scope = s) }) {
                        RadioButton(selected = d.scope == s, onClick = { d = d.copy(scope = s) })
                        Column { Text(s.displayName); Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Text("Approval", style = MaterialTheme.typography.titleSmall)
                ApprovalPolicy.entries.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { d = d.copy(approval = p) }) {
                        RadioButton(selected = d.approval == p, onClick = { d = d.copy(approval = p) })
                        Text(p.displayName)
                    }
                }
            }
        }
    }
}

@Composable
private fun Secret(label: String, value: String, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) { Icon(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (shown) "Hide" else "Show") }
        }
    )
}
