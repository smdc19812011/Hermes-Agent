package com.hermes.agent.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.agents.AgentLibraryRepository
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.domain.model.AgentProfile
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.Skill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val library: AgentLibraryRepository,
    providerRepository: ProviderRepository,
    toolRegistry: com.hermes.agent.data.tools.ToolRegistry,
    connectionRepository: com.hermes.agent.data.connections.ConnectionRepository
) : ViewModel() {
    val connections: StateFlow<List<com.hermes.agent.data.connections.Connection>> = connectionRepository.connections
    val skills: StateFlow<List<Skill>> = library.skills
    val agents: StateFlow<List<AgentProfile>> = library.agents
    val providers: StateFlow<List<Provider>> = providerRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val toolNames: List<String> = toolRegistry.all.map { it.name }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

    /** Runs a save and reports failures (bad names, duplicates) instead of closing the editor. */
    private fun attempt(onDone: () -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onSuccess { onDone() }.onFailure { _error.value = it.message ?: "Couldn't save." }
        }
    }

    fun saveSkill(skill: Skill, previousName: String?, onDone: () -> Unit) = attempt(onDone) { library.saveSkill(skill, previousName) }
    fun deleteSkill(name: String) = viewModelScope.launch { library.deleteSkill(name) }
    fun saveAgent(agent: AgentProfile, onDone: () -> Unit) = attempt(onDone) { library.saveAgent(agent) }
    fun deleteAgent(id: String) = viewModelScope.launch { library.deleteAgent(id) }
}
