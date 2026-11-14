package com.hermes.agent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.agent.domain.model.AgentSettings
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSettingsSection(settings: AgentSettings, providers: List<Provider>, onChange: ((AgentSettings) -> AgentSettings) -> Unit) {
    val usable = providers.filter { !it.isHermes && it.type != ProviderType.ANTHROPIC }
    Text(
        "Used by the AI's generate_image and text_to_speech tools (turn on \"Images, audio & video\" above). " +
            "Automatic picks an image-capable model from your OpenRouter or OpenAI provider; speech falls back to espeak in Termux.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ProviderPicker("Image provider", settings.imageProviderId, usable) { id -> onChange { it.copy(imageProviderId = id) } }
    OutlinedTextField(
        value = settings.imageModel,
        onValueChange = { v -> onChange { it.copy(imageModel = v.trim()) } },
        label = { Text("Image model") },
        placeholder = { Text("Automatic, e.g. google/gemini-3-pro-image") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Vision reader: looks at images, PDF pages and video frames for chat models that can't. Automatic prefers a vision model you " +
            "already use, then a free one on OpenRouter.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ProviderPicker("Vision reader provider", settings.visionProviderId, providers.filter { !it.isHermes }) { id -> onChange { it.copy(visionProviderId = id) } }
    OutlinedTextField(
        value = settings.visionModel,
        onValueChange = { v -> onChange { it.copy(visionModel = v.trim()) } },
        label = { Text("Vision reader model") },
        placeholder = { Text("Automatic, e.g. nvidia/nemotron-3.5-super-vl:free") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    ProviderPicker("Speech-to-text provider", settings.sttProviderId, usable) { id -> onChange { it.copy(sttProviderId = id) } }
    OutlinedTextField(
        value = settings.sttModel,
        onValueChange = { v -> onChange { it.copy(sttModel = v.trim()) } },
        label = { Text("Speech-to-text model") },
        placeholder = { Text("Automatic: whisper-1 (OpenAI) or whisper-large-v3 (Groq)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    ProviderPicker("Speech provider", settings.ttsProviderId, usable) { id -> onChange { it.copy(ttsProviderId = id) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = settings.ttsModel, onValueChange = { v -> onChange { it.copy(ttsModel = v.trim()) } },
            label = { Text("Speech model") }, placeholder = { Text("gpt-4o-mini-tts") }, singleLine = true, modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = settings.ttsVoice, onValueChange = { v -> onChange { it.copy(ttsVoice = v.trim()) } },
            label = { Text("Voice") }, placeholder = { Text("alloy") }, singleLine = true, modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPicker(label: String, selectedId: String, providers: List<Provider>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = providers.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = selected?.name ?: "Automatic",
            onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Automatic") }, onClick = { onSelect(""); open = false })
            providers.forEach { p ->
                DropdownMenuItem(text = { Text("${p.name} (${p.type.displayName})") }, onClick = { onSelect(p.id); open = false })
            }
        }
    }
}


