package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.model.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenToolLibrary: () -> Unit,
    onOpenAgents: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val loaded by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        val settings = loaded
        if (settings == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionHeader("Device access")
            DeviceAccessSection(
                enabled = settings.deviceAccess,
                onChange = { caps -> vm.update { it.copy(deviceAccess = caps) } }
            )
            OutlinedButton(onClick = onOpenToolLibrary, modifier = Modifier.fillMaxWidth()) {
                Text("Tool library: install programs for every chat")
            }
            OutlinedButton(onClick = onOpenAgents, modifier = Modifier.fillMaxWidth()) {
                Text("Skills & agents")
            }

            SectionHeader("Media")
            MediaSettingsSection(settings, providers) { transform -> vm.update(transform) }

            SectionHeader("Default routing for new chats")
            ChoiceList(
                options = RoutingStrategy.entries,
                selected = settings.globalRoutingStrategy,
                title = { it.displayName },
                description = { it.description },
                onSelect = { choice -> vm.update { it.copy(globalRoutingStrategy = choice) } }
            )

            SectionHeader("Default unrestricted mode for new chats")
            Text(
                "Controls which system prompt the app sends. Each chat can change it from the lock icon in its toolbar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChoiceList(
                options = UnrestrictedMode.entries,
                selected = settings.globalUnrestrictedMode,
                title = { it.displayName },
                description = { it.description },
                onSelect = { choice -> vm.update { it.copy(globalUnrestrictedMode = choice) } }
            )

            OutlinedTextField(
                value = settings.customUnrestrictedPrompt,
                onValueChange = { text -> vm.update { it.copy(customUnrestrictedPrompt = text) } },
                label = { Text("Custom unrestricted prompt") },
                supportingText = { Text("Used by chats set to \"Custom system prompt\".") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10
            )

            SectionHeader("Default system prompt")
            OutlinedTextField(
                value = settings.globalSystemPrompt,
                onValueChange = { text -> vm.update { it.copy(globalSystemPrompt = text) } },
                label = { Text("System prompt (optional)") },
                placeholder = { Text("Leave blank to use the model's default.") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8
            )

            SectionHeader("Fallback and network")
            SliderSetting(
                title = "Models to try per message",
                valueLabel = "${settings.maxProviderAttempts}",
                value = settings.maxProviderAttempts.toFloat(),
                range = 1f..10f,
                steps = 8,
                onChange = { v -> vm.update { it.copy(maxProviderAttempts = v.roundToInt()) } }
            )
            SliderSetting(
                title = "Read timeout",
                valueLabel = "${settings.requestTimeoutSeconds}s",
                value = settings.requestTimeoutSeconds.toFloat(),
                range = 15f..300f,
                steps = 18,
                onChange = { v -> vm.update { it.copy(requestTimeoutSeconds = v.roundToInt()) } }
            )

            SectionHeader("Display")
            ChoiceList(
                options = AppTheme.entries,
                selected = settings.theme,
                title = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                description = { null },
                onSelect = { choice -> vm.update { it.copy(theme = choice) } }
            )
            ListItem(
                headlineContent = { Text("Show model under replies") },
                supportingContent = { Text("Label each reply with the model that wrote it.", style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Switch(
                        checked = settings.showProviderBadge,
                        onCheckedChange = { on -> vm.update { it.copy(showProviderBadge = on) } }
                    )
                }
            )

            SectionHeader("About")
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Hermes Agent Android", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android port of NousResearch/hermes-agent with multi-provider routing and unrestricted mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Version 1.0.0",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceList(
    options: List<T>,
    selected: T,
    title: (T) -> String,
    description: (T) -> String?,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Card(
                onClick = { onSelect(option) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isSelected, onClick = { onSelect(option) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(title(option), style = MaterialTheme.typography.titleSmall)
                        description(option)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
