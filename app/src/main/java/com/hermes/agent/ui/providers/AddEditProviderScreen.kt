package com.hermes.agent.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.domain.model.ProviderType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditProviderScreen(
    providerId: String?,
    onBack: () -> Unit,
    vm: AddEditProviderViewModel = hiltViewModel()
) {
    LaunchedEffect(providerId) { vm.init(providerId) }

    val state by vm.state.collectAsStateWithLifecycle()
    var showApiKey by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }

    state.error?.let { err ->
        LaunchedEffect(err) {
            kotlinx.coroutines.delay(3000)
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEdit) "Edit Provider" else "Add Provider") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(
                        onClick = { vm.save(onBack) },
                        enabled = !state.isSaving
                    ) {
                        if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            state.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = { vm.setName(it) },
                label = { Text("Display name") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("My OpenRouter") }
            )

            // Provider type dropdown
            ExposedDropdownMenuBox(
                expanded = showTypeMenu,
                onExpandedChange = { showTypeMenu = it }
            ) {
                OutlinedTextField(
                    value = state.type.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider type") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showTypeMenu) }
                )
                ExposedDropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                    ProviderType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayName) },
                            onClick = { vm.setType(type); showTypeMenu = false }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = { vm.setApiKey(it) },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("sk-or-…") },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle visibility")
                    }
                }
            )

            // Base URL — always shown but pre-filled
            OutlinedTextField(
                value = state.baseUrl.ifBlank { state.type.baseUrl },
                onValueChange = { vm.setBaseUrl(it) },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(state.type.baseUrl) },
                singleLine = true
            )

            OutlinedTextField(
                value = state.defaultModel,
                onValueChange = { vm.setDefaultModel(it) },
                label = { Text("Default model") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("model-id") },
                singleLine = true
            )

            // Common model suggestions
            ModelSuggestions(type = state.type, onSelect = { vm.setDefaultModel(it) })

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.isEnabled, onCheckedChange = { vm.setEnabled(it) })
            }

            if (state.type == ProviderType.OPENROUTER) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        "OpenRouter tip: use nousresearch/hermes-3-llama-3.1-405b for the full Hermes 3 experience, or any of 300+ models available.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSuggestions(type: ProviderType, onSelect: (String) -> Unit) {
    val suggestions = when (type) {
        ProviderType.OPENROUTER -> listOf(
            "nousresearch/hermes-3-llama-3.1-405b",
            "nousresearch/hermes-3-llama-3.1-70b",
            "anthropic/claude-sonnet-5-5",
            "openai/gpt-4o",
            "meta-llama/llama-3.3-70b-instruct",
            "google/gemini-2.5-flash"
        )
        ProviderType.OPENAI -> listOf("gpt-4o", "gpt-4o-mini", "o3-mini", "o1")
        ProviderType.ANTHROPIC -> listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")
        ProviderType.GROQ -> listOf("llama-3.3-70b-versatile", "mixtral-8x7b-32768", "gemma2-9b-it")
        ProviderType.TOGETHER -> listOf("meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo", "mistralai/Mixtral-8x7B-Instruct-v0.1")
        ProviderType.MISTRAL -> listOf("mistral-large-latest", "mistral-small-latest", "codestral-latest")
        ProviderType.DEEPSEEK -> listOf("deepseek-chat", "deepseek-coder")
        ProviderType.CUSTOM -> emptyList()
    }

    if (suggestions.isEmpty()) return

    Column {
        Text(
            "Quick select",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { model ->
                SuggestionChip(
                    onClick = { onSelect(model) },
                    label = { Text(model, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}
