package com.hermes.agent.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.ModelCatalogRepository
import com.hermes.agent.data.repository.ModelInfo
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed class CatalogState {
    data object Idle : CatalogState()
    data object NeedsKey : CatalogState()
    data object Loading : CatalogState()
    data class Loaded(val models: List<ModelInfo>) : CatalogState()
    data class Failed(val message: String) : CatalogState()
}

data class AddEditProviderState(
    val name: String = "",
    val type: ProviderType = ProviderType.OPENROUTER,
    val apiKey: String = "",
    val baseUrl: String = ProviderType.OPENROUTER.baseUrl,
    /** Ordered: index 0 is the primary model, the rest are fallbacks. */
    val models: List<String> = emptyList(),
    val isEnabled: Boolean = true,
    val isEdit: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val catalog: CatalogState = CatalogState.Idle
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddEditProviderViewModel @Inject constructor(
    private val repository: ProviderRepository,
    private val catalog: ModelCatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AddEditProviderState())
    val state: StateFlow<AddEditProviderState> = _state.asStateFlow()

    private var editId: String? = null
    private val refetch = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        // Detect models shortly after the key or endpoint stops changing.
        merge(
            _state.map { Triple(it.type, it.apiKey.trim(), it.baseUrl.trim()) }.distinctUntilChanged().debounce(800),
            refetch.map { _state.value.let { Triple(it.type, it.apiKey.trim(), it.baseUrl.trim()) } }
        )
            .mapLatest { (type, key, base) -> loadCatalog(type, key, base) }
            .onEach { result -> _state.update { it.copy(catalog = result) } }
            .launchIn(viewModelScope)
    }

    private suspend fun loadCatalog(type: ProviderType, key: String, base: String): CatalogState {
        val url = base.ifBlank { type.baseUrl }
        if (url.isBlank()) return CatalogState.Idle
        if (key.isBlank() && !catalog.canFetchWithoutKey(type)) return CatalogState.NeedsKey
        _state.update { it.copy(catalog = CatalogState.Loading) }
        return try {
            CatalogState.Loaded(catalog.fetch(type, url, key))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CatalogState.Failed(e.message ?: "Couldn't load models")
        }
    }

    fun init(providerId: String?) {
        if (providerId == null || editId == providerId) return
        editId = providerId
        viewModelScope.launch {
            repository.getById(providerId)?.let { p ->
                _state.update {
                    it.copy(
                        name = p.name,
                        type = p.type,
                        apiKey = p.apiKey,
                        baseUrl = p.baseUrl,
                        models = p.models,
                        isEnabled = p.isEnabled,
                        isEdit = true
                    )
                }
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setType(v: ProviderType) = _state.update {
        if (v == it.type) it else it.copy(type = v, baseUrl = v.baseUrl, models = if (v == ProviderType.HERMES) listOf("hermes-agent") else emptyList())
    }
    fun setApiKey(v: String) = _state.update { it.copy(apiKey = v) }
    fun setBaseUrl(v: String) = _state.update { it.copy(baseUrl = v) }
    fun setEnabled(v: Boolean) = _state.update { it.copy(isEnabled = v) }
    fun retryCatalog() = refetch.tryEmit(Unit)

    fun addModel(id: String) = _state.update {
        val clean = id.trim()
        if (clean.isEmpty() || clean in it.models) it else it.copy(models = it.models + clean, error = null)
    }

    fun removeModel(id: String) = _state.update { it.copy(models = it.models - id) }

    fun moveModel(id: String, up: Boolean) = _state.update {
        val list = it.models.toMutableList()
        val i = list.indexOf(id)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) it
        else it.copy(models = list.apply { this[i] = this[j].also { this[j] = this[i] } })
    }

    fun save(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.name.isBlank()) { _state.update { it.copy(error = "Name is required") }; return }
        if (s.apiKey.isBlank() && s.type != ProviderType.CUSTOM) {
            _state.update { it.copy(error = "API key is required") }; return
        }
        if (s.models.isEmpty()) { _state.update { it.copy(error = "Choose at least one model") }; return }
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
                models = s.models,
                isEnabled = s.isEnabled,
                priority = existing?.priority ?: repository.nextPriority(),
                // The timing reflects the old primary; keep it only if that didn't change.
                avgResponseMs = if (existing?.type == s.type && existing.defaultModel == s.models.first()) existing.avgResponseMs else 0L
            )
            repository.save(provider)
            onSuccess()
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
