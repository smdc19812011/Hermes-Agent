package com.hermes.agent.ui.toolbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.termux.TermuxBridge
import com.hermes.agent.data.toolbox.LibraryTool
import com.hermes.agent.data.toolbox.SourceType
import com.hermes.agent.data.toolbox.ToolLibraryRepository
import com.hermes.agent.data.toolbox.ToolSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ToolLibraryUiState(
    val tools: List<LibraryTool> = emptyList(),
    val address: String = "",
    val forcedType: SourceType? = null,
    val detected: String? = null,
    val error: String? = null,
    val message: String? = null,
    val log: String = "",
    val termuxReady: Boolean = false
)

@HiltViewModel
class ToolLibraryViewModel @Inject constructor(
    private val library: ToolLibraryRepository,
    private val termux: TermuxBridge
) : ViewModel() {

    private val _state = MutableStateFlow(ToolLibraryUiState())
    val state: StateFlow<ToolLibraryUiState> = _state.asStateFlow()

    init {
        library.tools.onEach { list -> _state.update { it.copy(tools = list) } }.launchIn(viewModelScope)
        refresh()
    }

    fun refresh() = _state.update { it.copy(termuxReady = termux.isInstalled() && termux.hasPermission()) }

    fun setAddress(v: String) = _state.update { it.copy(address = v, error = null, detected = describe(v, it.forcedType)) }

    fun setForcedType(type: SourceType?) = _state.update { it.copy(forcedType = type, detected = describe(it.address, type)) }

    private fun describe(address: String, forced: SourceType?): String? {
        if (address.isBlank()) return null
        return runCatching { ToolSource.parse(address, forced) }
            .fold({ "${it.type.displayName}: ${it.address}" }, { it.message })
    }

    fun addAndInstall(address: String = _state.value.address) {
        viewModelScope.launch {
            val tool = runCatching { library.add(address, _state.value.forcedType.takeIf { address == _state.value.address }) }
                .getOrElse { e -> _state.update { it.copy(error = e.message) }; return@launch }
            _state.update { it.copy(address = "", detected = null, forcedType = null, error = null) }
            install(tool.id)
        }
    }

    /** Quick-add chips call this: redownloads/reinstalls if the preset is already in the library, whatever its status. */
    fun quickAdd(address: String) {
        val existing = _state.value.tools.firstOrNull { it.type == SourceType.PKG && it.address == address }
        if (existing != null) install(existing.id) else addAndInstall(address)
    }

    fun install(id: String) {
        viewModelScope.launch {
            val outcome = library.install(id)
            _state.update {
                it.copy(
                    message = if (outcome.ok) "${outcome.tool.name} installed." else "${outcome.tool.name}: ${outcome.tool.message}",
                    log = outcome.log.lines().takeLast(40).joinToString("\n")
                )
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { _state.update { it.copy(message = library.remove(id)) } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
