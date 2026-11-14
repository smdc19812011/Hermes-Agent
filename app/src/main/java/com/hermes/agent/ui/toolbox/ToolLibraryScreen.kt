package com.hermes.agent.ui.toolbox

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.toolbox.InstallStatus
import com.hermes.agent.data.toolbox.LibraryTool
import com.hermes.agent.data.toolbox.SourceType
import com.hermes.agent.data.toolbox.ToolSource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToolLibraryScreen(
    onBack: () -> Unit,
    onOpenRuntime: () -> Unit,
    vm: ToolLibraryViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var typeMenu by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<LibraryTool?>(null) }
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }

    removeTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove ${t.name}?") },
            text = { Text("It will be uninstalled from Termux and no chat will be able to use it.") },
            confirmButton = { TextButton(onClick = { vm.remove(t.id); removeTarget = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tool library") },
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
                "Programs installed here live in Termux and are shared by every chat, the AI's shell tool, the code sandbox and Hermes. " +
                    "With Termux shell access on, the AI can also add tools it needs, after you approve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!state.termuxReady) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Termux isn't set up yet. Finish steps 1–3 on the Agent runtime screen to install tools.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenRuntime) { Text("Open Agent runtime") }
                    }
                }
            }

            OutlinedTextField(
                value = state.address,
                onValueChange = vm::setAddress,
                label = { Text("Address") },
                placeholder = { Text("nmap · pip:requests · owner/repo · https://…") },
                supportingText = { (state.error ?: state.detected)?.let { Text(it) } },
                isError = state.error != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AssistChip(
                        onClick = { typeMenu = true },
                        label = { Text(state.forcedType?.displayName ?: "Detect type") },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                    )
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        DropdownMenuItem(text = { Text("Detect type") }, onClick = { vm.setForcedType(null); typeMenu = false })
                        SourceType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.displayName) }, onClick = { vm.setForcedType(t); typeMenu = false })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(enabled = state.termuxReady && state.address.isNotBlank(), onClick = { vm.addAndInstall() }) { Text("Add & install") }
            }

            Text("Quick add", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolSource.PRESETS.forEach { p ->
                    val present = state.tools.any { it.type == SourceType.PKG && it.address == p.address }
                    SuggestionChip(
                        enabled = state.termuxReady && !present,
                        onClick = { vm.addAndInstall(p.address) },
                        label = { Text(p.label) }
                    )
                }
            }
            Text(
                "Android SDK tools come from Termux's android-tools package: Google's SDK downloads are desktop builds that don't run on phones. " +
                    "GitHub releases install the file built for this phone's processor; Linux binaries must be static or Android builds to run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.message?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                        IconButton(onClick = vm::clearMessage) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(16.dp)) }
                    }
                }
            }

            Text("Installed and pending (${state.tools.size})", style = MaterialTheme.typography.titleSmall)
            if (state.tools.isEmpty()) {
                Text("Nothing yet. Add an address above or tap a quick-add tool.", style = MaterialTheme.typography.bodySmall)
            }
            state.tools.forEach { tool ->
                ToolRow(tool, state.termuxReady, onInstall = { vm.install(tool.id) }, onRemove = { removeTarget = tool })
            }

            HorizontalDivider()
            UsbDriversSection(termuxReady = state.termuxReady)

            if (state.log.isNotBlank()) {
                Text("Last install output", style = MaterialTheme.typography.titleSmall)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    SelectionContainer {
                        Text(
                            state.log, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: LibraryTool, termuxReady: Boolean, onInstall: () -> Unit, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (tool.status) {
                InstallStatus.INSTALLED -> Icon(Icons.Default.CheckCircle, "Installed", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                InstallStatus.INSTALLING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                InstallStatus.FAILED -> Icon(Icons.Default.ErrorOutline, "Failed", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                InstallStatus.PENDING -> Icon(Icons.Default.Schedule, "Not installed", Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.titleSmall)
                Text("${tool.type.displayName} · ${tool.address}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tool.message.isNotBlank()) {
                    Text(
                        tool.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tool.status == InstallStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(enabled = termuxReady && tool.status != InstallStatus.INSTALLING, onClick = onInstall) {
                Icon(if (tool.status == InstallStatus.INSTALLED) Icons.Default.Refresh else Icons.Default.Download,
                    if (tool.status == InstallStatus.INSTALLED) "Reinstall" else "Install")
            }
            IconButton(enabled = tool.status != InstallStatus.INSTALLING, onClick = onRemove) { Icon(Icons.Default.Delete, "Remove") }
        }
    }
}
