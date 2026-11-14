package com.hermes.agent.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.repository.ModelInfo
import com.hermes.agent.domain.model.ProviderType

fun rankLabel(position: Int): String = when (position) {
    1 -> "Primary"
    2 -> "Secondary"
    else -> {
        val suffix = if (position % 100 in 11..13) "th" else when (position % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        "$position$suffix"
    }
}

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
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ModelPickerSheet(
            catalog = state.catalog,
            selected = state.models,
            onToggle = { id -> if (id in state.models) vm.removeModel(id) else vm.addModel(id) },
            onRetry = vm::retryCatalog,
            onDismiss = { showPicker = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEdit) "Edit provider" else "Add provider") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { vm.save(onBack) }, enabled = !state.isSaving) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Save")
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
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        IconButton(onClick = vm::clearError) { Icon(Icons.Default.Close, "Dismiss") }
                    }
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::setName,
                label = { Text("Display name") },
                placeholder = { Text("My OpenRouter") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(expanded = showTypeMenu, onExpandedChange = { showTypeMenu = it }) {
                OutlinedTextField(
                    value = state.type.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider type") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showTypeMenu) }
                )
                ExposedDropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                    ProviderType.entries.forEach { type ->
                        DropdownMenuItem(text = { Text(type.displayName) }, onClick = { vm.setType(type); showTypeMenu = false })
                    }
                }
            }

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = vm::setApiKey,
                label = { Text("API key") },
                placeholder = { Text(if (state.type == ProviderType.OPENROUTER) "sk-or-…" else "Paste your key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showApiKey) "Hide key" else "Show key")
                    }
                }
            )

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = vm::setBaseUrl,
                label = { Text("Base URL") },
                placeholder = { Text(state.type.baseUrl.ifBlank { "https://your-endpoint/v1" }) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            ModelsSection(state, onAdd = { showPicker = true }, onMove = vm::moveModel, onRemove = vm::removeModel, onRetry = vm::retryCatalog)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.isEnabled, onCheckedChange = vm::setEnabled)
            }
        }
    }
}

@Composable
private fun ModelsSection(
    state: AddEditProviderState,
    onAdd: () -> Unit,
    onMove: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onRetry: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Models", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tried in order. If the primary fails, the secondary is used, and so on, before the next provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CatalogStatus(state.catalog, onRetry)

        state.models.forEachIndexed { index, model ->
            Card(shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(
                            rankLabel(index + 1),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(model, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onMove(model, true) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { onMove(model, false) }, enabled = index < state.models.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = { onRemove(model) }) { Icon(Icons.Default.Close, "Remove $model") }
                }
            }
        }

        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.models.isEmpty()) "Choose models" else "Add another model")
        }
    }
}

@Composable
private fun CatalogStatus(catalog: CatalogState, onRetry: () -> Unit) {
    val (text, isError) = when (catalog) {
        CatalogState.Idle -> return
        CatalogState.NeedsKey -> "Enter the API key to load the models this provider offers." to false
        CatalogState.Loading -> "Finding available models…" to false
        is CatalogState.Loaded -> "${catalog.models.size} models available." to false
        is CatalogState.Failed -> "Couldn't load models: ${catalog.message}" to true
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (catalog) {
            CatalogState.Loading -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            is CatalogState.Loaded -> Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            is CatalogState.Failed -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            else -> Icon(Icons.Default.Key, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (catalog is CatalogState.Failed) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    catalog: CatalogState,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val all = (catalog as? CatalogState.Loaded)?.models.orEmpty()
    val filtered = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter { q in it.id.lowercase() || q in it.label.orEmpty().lowercase() }
    }
    val manual = query.trim().takeIf { q -> q.isNotEmpty() && all.none { it.id == q } && q !in selected }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Choose models", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap in order of preference: first tap is Primary, next is Secondary. Tap again to remove.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search, or type a model ID") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            CatalogStatus(catalog, onRetry)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            manual?.let { id ->
                item(key = "manual") {
                    ListItem(
                        headlineContent = { Text("Add \"$id\"") },
                        supportingContent = { Text("Use this model ID as typed") },
                        leadingContent = { Icon(Icons.Default.Add, null) },
                        modifier = Modifier.clickable { onToggle(id); query = "" }
                    )
                }
            }
            // Picked models not in the catalog (typed by hand) stay visible so they can be removed here too.
            items(selected.filter { s -> all.none { it.id == s } && (query.isBlank() || query.trim().lowercase() in s.lowercase()) }, key = { "sel-$it" }) { id ->
                ModelRow(ModelInfo(id, null, null), rank = selected.indexOf(id) + 1, onClick = { onToggle(id) })
            }
            items(filtered, key = { it.id }) { model ->
                val rank = selected.indexOf(model.id) + 1
                ModelRow(model, rank, onClick = { onToggle(model.id) })
            }
        }
    }
}

@Composable
private fun ModelRow(model: ModelInfo, rank: Int, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(model.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = {
            val details = listOfNotNull(
                model.label,
                model.contextLength?.let { "${it / 1000}k context" },
                when (model.supportsTools) { true -> "tools"; false -> "no tools"; null -> null }
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            if (rank > 0) {
                Box(
                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(rankLabel(rank), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    )
}
