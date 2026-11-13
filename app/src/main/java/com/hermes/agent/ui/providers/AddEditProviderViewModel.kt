package com.hermes.agent.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AddEditProviderState(
    val name: String = "",
    val type: ProviderType = ProviderType.OPENROUTER,
    val apiKey: String = "",
    val baseUrl: String = "",
    val defaultModel: String = "",
    val isEnabled: Boolean = true,
    val isEdit: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AddEditProviderViewModel @Inject constructor(
    private val repository: ProviderRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AddEditProviderState())
    val state: StateFlow<AddEditProviderState> = _state.asStateFlow()

    private var editId: String? = null

    fun init(providerId: String?) {
        if (providerId == null) return
        editId = providerId
        viewModelScope.launch {
            repository.getById(providerId)?.let { p ->
                _state.update {
                    it.copy(
                        name = p.name,
                        type = p.type,
                        apiKey = p.apiKey,
                        baseUrl = p.baseUrl,
                        defaultModel = p.defaultModel,
                        isEnabled = p.isEnabled,
                        isEdit = true
                    )
                }
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setType(v: ProviderType) = _state.update { it.copy(type = v, baseUrl = v.baseUrl, defaultModel = defaultModelFor(v)) }
    fun setApiKey(v: String) = _state.update { it.copy(apiKey = v) }
    fun setBaseUrl(v: String) = _state.update { it.copy(baseUrl = v) }
    fun setDefaultModel(v: String) = _state.update { it.copy(defaultModel = v) }
    fun setEnabled(v: Boolean) = _state.update { it.copy(isEnabled = v) }

    fun save(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.name.isBlank()) { _state.update { it.copy(error = "Name is required") }; return }
        if (s.apiKey.isBlank() && s.type != ProviderType.CUSTOM) {
            _state.update { it.copy(error = "API key is required") }; return
        }
        if (s.defaultModel.isBlank()) { _state.update { it.copy(error = "Model is required") }; return }
        val baseUrl = s.baseUrl.trim().ifBlank { s.type.baseUrl }.trimEnd('/')
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            _state.update { it.copy(error = "Base URL must start with https:// or http://") }; return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val existing = editId?.let { repository.getById(it) }
            val provider = Provider(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = s.name.trim(),
                type = s.type,
                apiKey = s.apiKey.trim(),
                baseUrl = baseUrl,
                defaultModel = s.defaultModel.trim(),
                isEnabled = s.isEnabled,
                priority = existing?.priority ?: repository.nextPriority(),
                avgResponseMs = if (existing?.type == s.type && existing.defaultModel == s.defaultModel.trim()) existing.avgResponseMs else 0L
            )
            repository.save(provider)
            onSuccess()
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun defaultModelFor(type: ProviderType) = when (type) {
        ProviderType.OPENROUTER -> "nousresearch/hermes-3-llama-3.1-405b"
        ProviderType.OPENAI -> "gpt-4o"
        ProviderType.ANTHROPIC -> "claude-sonnet-5-5"
        ProviderType.GROQ -> "llama-3.3-70b-versatile"
        ProviderType.TOGETHER -> "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo"
        ProviderType.MISTRAL -> "mistral-large-latest"
        ProviderType.DEEPSEEK -> "deepseek-chat"
        ProviderType.CUSTOM -> ""
    }
}
