package com.hermes.agent.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.ConversationRepository
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.domain.model.Conversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    agentLibrary: com.hermes.agent.data.agents.AgentLibraryRepository
) : ViewModel() {

    val agents: StateFlow<List<com.hermes.agent.domain.model.AgentProfile>> = agentLibrary.agents

    private val searchQuery = MutableStateFlow("")

    val conversations: StateFlow<List<Conversation>> = searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) repository.observeAll() else repository.search(query.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearch(query: String) {
        searchQuery.value = query
    }

    fun newConversation(agentId: String? = null, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val conversation = Conversation(
                routingStrategy = settings.globalRoutingStrategy,
                unrestrictedMode = settings.globalUnrestrictedMode,
                agentId = agentId
            )
            repository.save(conversation)
            onCreated(conversation.id)
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch { repository.delete(conversation) }
    }
}
