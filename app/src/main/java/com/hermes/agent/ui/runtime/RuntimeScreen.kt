package com.hermes.agent.ui.runtime

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.runtime.HermesScripts
import com.hermes.agent.data.runtime.InstallMethod
import com.hermes.agent.data.runtime.RuntimeLocation
import com.hermes.agent.data.runtime.RuntimeSettings
import com.hermes.agent.data.runtime.SandboxBackend
import com.hermes.agent.data.runtime.ServerStatus
import com.hermes.agent.data.termux.TermuxBridge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeScreen(
    onBack: () -> Unit,
    onOpenSandbox: () -> Unit,
    vm: RuntimeViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshTermux()
    }

    LifecycleResumeEffect(Unit) {
        vm.refreshTermux()
        vm.startPolling()
        onPauseOrDispose { vm.stopPolling() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent runtime") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onOpenSandbox) { Icon(Icons.Default.Terminal, "Code sandbox") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        val s = state.settings
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(state, s, vm)

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RuntimeLocation.entries.forEachIndexed { i, loc ->
                    SegmentedButton(
                        selected = s.location == loc,
                        onClick = { vm.update { it.copy(location = loc) } },
                        shape = SegmentedButtonDefaults.itemShape(i, RuntimeLocation.entries.size)
                    ) { Text(if (loc == RuntimeLocation.THIS_PHONE) "On this phone" else "Remote server") }
                }
            }

            if (s.location == RuntimeLocation.THIS_PHONE) {
                Step(1, "Install Termux", done = state.termuxInstalled) {
                    Text(
                        "Hermes runs inside Termux. Install it from F-Droid or GitHub; the Play Store build is outdated.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!state.termuxInstalled) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
                        }) { Text("Get Termux on F-Droid") }
                    }
                }
                Step(2, "Let this app send commands to Termux", done = null) {
                    Text("Open Termux once and run this command:", style = MaterialTheme.typography.bodySmall)
                    CommandBox(HermesScripts.ALLOW_EXTERNAL_APPS_COMMAND)
                    OutlinedButton(
                        enabled = state.termuxInstalled,
                        onClick = {
                            context.packageManager.getLaunchIntentForPackage(TermuxBridge.TERMUX_PACKAGE)
                                ?.let { context.startActivity(it) }
                        }
                    ) { Text("Open Termux") }
                }
                Step(3, "Grant the Termux command permission", done = state.termuxPermission) {
                    if (!state.termuxPermission) {
                        Button(
                            enabled = state.termuxInstalled,
                            onClick = { permissionLauncher.launch(TermuxBridge.PERMISSION_RUN_COMMAND) }
                        ) { Text("Grant permission") }
                    }
                }
                Step(4, "Install Hermes Agent", done = state.hermesVersion != null) {
                    state.hermesVersion?.let {
                        Text("Installed: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    ChoiceCards(
                        options = InstallMethod.entries,
                        selected = s.installMethod,
                        title = { it.displayName },
                        description = { it.description },
                        onSelect = { m -> vm.update { it.copy(installMethod = m) } }
                    )
                    Button(
                        enabled = state.termuxPermission && state.busy == null,
                        onClick = { vm.install() }
                    ) { Text(if (state.hermesVersion == null) "Install" else "Reinstall / update") }
                }
                Step(5, "Choose the model and sandbox", done = s.configured) {
                    if (!s.configured) {
                        Text(
                            "Required before chatting: Hermes needs a model to think with.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    ModelPicker(state, s, vm)
                    Text("Where tools run code", style = MaterialTheme.typography.titleSmall)
                    ChoiceCards(
                        options = SandboxBackend.entries,
                        selected = s.backend,
                        title = { it.displayName },
                        description = { it.description },
                        onSelect = { b -> vm.update { it.copy(backend = b) } }
                    )
                    BackendFields(s, vm)
                    Button(
                        enabled = state.hermesVersion != null && state.busy == null,
                        onClick = { vm.configure() }
                    ) { Text("Save configuration to Hermes") }
                }
            } else {
                RemoteSection(state, s, vm)
            }

            LogSection(state, vm)
        }
    }
}

@Composable
private fun StatusCard(state: RuntimeUiState, s: RuntimeSettings, vm: RuntimeViewModel) {
    val (color, text) = when (val st = state.server) {
        is ServerStatus.Online -> Color(0xFF56CF8A) to "Running"
        ServerStatus.Offline -> MaterialTheme.colorScheme.outline to "Stopped"
        is ServerStatus.Error -> MaterialTheme.colorScheme.error to "Error: ${st.message}"
        ServerStatus.Unknown -> MaterialTheme.colorScheme.outline to "Not set up"
    }
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hermes gateway", style = MaterialTheme.typography.titleMedium)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (s.location == RuntimeLocation.THIS_PHONE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val running = state.server is ServerStatus.Online
                    Button(enabled = !running && state.busy == null && state.termuxPermission, onClick = { vm.start() }) { Text("Start") }
                    OutlinedButton(enabled = state.busy == null && state.termuxPermission, onClick = { vm.stop() }) { Text("Stop") }
                    OutlinedButton(enabled = running && state.busy == null, onClick = { vm.restart() }) { Text("Restart") }
                }
                Text(
                    "Android may stop Termux in the background. Exempt Termux from battery optimisation to keep the agent running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.message?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                        IconButton(onClick = { vm.clearMessage() }) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, done: Boolean?, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(
                        if (done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done == true) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    else Text("$number", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun CommandBox(command: String) {
    val clipboard = LocalClipboardManager.current
    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)
                )
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
                Icon(Icons.Default.ContentCopy, "Copy command", Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceCards(
    options: List<T>,
    selected: T,
    title: (T) -> String,
    description: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Card(
                onClick = { onSelect(option) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = { onSelect(option) })
                    Column {
                        Text(title(option), style = MaterialTheme.typography.titleSmall)
                        Text(description(option), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(state: RuntimeUiState, s: RuntimeSettings, vm: RuntimeViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.llmProviders.firstOrNull { it.id == s.modelProviderId }
    Text("Model Hermes thinks with", style = MaterialTheme.typography.titleSmall)
    if (state.llmProviders.isEmpty()) {
        Text("Add an OpenRouter, Anthropic or other provider first; Hermes uses its key and model.", style = MaterialTheme.typography.bodySmall)
        return
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { "${it.name} · ${it.defaultModel}" } ?: "Keep Hermes's current model",
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Keep Hermes's current model") }, onClick = {
                vm.update { it.copy(modelProviderId = "") }; expanded = false
            })
            state.llmProviders.forEach { p ->
                DropdownMenuItem(text = { Text("${p.name} · ${p.defaultModel}") }, onClick = {
                    vm.update { it.copy(modelProviderId = p.id) }; expanded = false
                })
            }
        }
    }
}

@Composable
private fun BackendFields(s: RuntimeSettings, vm: RuntimeViewModel) {
    when (s.backend) {
        SandboxBackend.LOCAL -> Text(
            "Python, Node.js and Bash run in Termux. Hermes asks before running commands it considers dangerous.",
            style = MaterialTheme.typography.bodySmall
        )
        SandboxBackend.SSH -> {
            Field("Host", s.sshHost) { v -> vm.update { it.copy(sshHost = v) } }
            Field("User", s.sshUser) { v -> vm.update { it.copy(sshUser = v) } }
            Field("Port", s.sshPort) { v -> vm.update { it.copy(sshPort = v.filter(Char::isDigit)) } }
            Field("Private key path in Termux", s.sshKeyPath) { v -> vm.update { it.copy(sshKeyPath = v) } }
            Text("Create a key in Termux with ssh-keygen and add the .pub file to the server's authorized_keys.", style = MaterialTheme.typography.bodySmall)
        }
        SandboxBackend.DOCKER -> {
            Field("Docker host", s.dockerHost, placeholder = "ssh://user@server") { v -> vm.update { it.copy(dockerHost = v) } }
            Field("Image", s.dockerImage) { v -> vm.update { it.copy(dockerImage = v) } }
            Text(
                "Hermes calls the docker CLI with DOCKER_HOST set, so containers run on that machine. The docker CLI is installed in Termux when you save.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        SandboxBackend.MODAL -> {
            Field("Token ID", s.modalTokenId) { v -> vm.update { it.copy(modalTokenId = v) } }
            Field("Token secret", s.modalTokenSecret, secret = true) { v -> vm.update { it.copy(modalTokenSecret = v) } }
        }
        SandboxBackend.DAYTONA -> Field("API key", s.daytonaApiKey, secret = true) { v -> vm.update { it.copy(daytonaApiKey = v) } }
    }
}

@Composable
private fun RemoteSection(state: RuntimeUiState, s: RuntimeSettings, vm: RuntimeViewModel) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect to Hermes on a server", style = MaterialTheme.typography.titleMedium)
            Text(
                "A server can use every Hermes backend, including local Docker. On the server, run:",
                style = MaterialTheme.typography.bodySmall
            )
            CommandBox("hermes config set API_SERVER_ENABLED true && hermes config set API_SERVER_KEY <your-key> && hermes config set API_SERVER_HOST 0.0.0.0 && hermes gateway run")
            Text(
                "Reach it over a VPN such as Tailscale, or put HTTPS in front of it. The key is sent with every request.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Field("Server address", s.remoteUrl, placeholder = "http://100.64.0.5:8642") { v -> vm.update { it.copy(remoteUrl = v) } }
            Field("API server key", s.remoteKey, secret = true) { v -> vm.update { it.copy(remoteKey = v) } }
            Button(enabled = state.busy == null, onClick = { vm.connectRemote() }) { Text("Connect") }
        }
    }
}

@Composable
private fun LogSection(state: RuntimeUiState, vm: RuntimeViewModel) {
    if (state.settings?.location != RuntimeLocation.THIS_PHONE) return
    Text("Logs", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("gateway.log" to "Gateway", "install.log" to "Install", "configure.log" to "Configure").forEach { (file, label) ->
            AssistChip(enabled = state.termuxPermission, onClick = { vm.showLog(file) }, label = { Text(label) })
        }
    }
    if (state.log.isNotBlank()) {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(8.dp)) {
            SelectionContainer {
                Text(
                    state.log,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String? = null, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
}
