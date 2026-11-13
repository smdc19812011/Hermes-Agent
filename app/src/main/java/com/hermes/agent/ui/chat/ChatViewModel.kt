package com.hermes.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.ConversationRepository
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.domain.model.*
import com.hermes.agent.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val streamingContent: String = "",
    val activeProviderName: String? = null,
    val activeModel: String? = null,
    val isLoading: Boolean = false,
    val notice: String? = null,
    val showProviderBadge: Boolean = true
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var initializedFor: String? = null
    private var sendJob: Job? = null

    init {
        settingsRepository.settings
            .onEach { s -> _state.update { it.copy(showProviderBadge = s.showProviderBadge) } }
            .launchIn(viewModelScope)
    }

    fun init(conversationId: String) {
        if (initializedFor == conversationId) return
        initializedFor = conversationId
        conversationRepository.observeById(conversationId)
            .onEach { conv -> _state.update { it.copy(conversation = conv) } }
            .launchIn(viewModelScope)
        conversationRepository.observeMessages(conversationId)
            .onEach { msgs -> _state.update { it.copy(messages = msgs) } }
            .launchIn(viewModelScope)
    }

    fun send(input: String) {
        val conversation = _state.value.conversation ?: return
        if (_state.value.isLoading || input.isBlank()) return

        _state.update { it.copy(isLoading = true, streamingContent = "", notice = null) }
        val history = _state.value.messages

        sendJob = viewModelScope.launch {
            sendMessageUseCase(conversation, input.trim(), history)
                .catch { e -> _state.update { it.copy(notice = e.message ?: "Request failed") } }
                .onCompletion { _state.update { it.copy(isLoading = false, streamingContent = "") } }
                .collect { chunk ->
                    when (chunk) {
                        is StreamChunk.ProviderSelected -> _state.update {
                            it.copy(
                                activeProviderName = chunk.providerName,
                                activeModel = chunk.model,
                                streamingContent = ""
                            )
                        }
                        is StreamChunk.Token -> _state.update {
                            it.copy(streamingContent = it.streamingContent + chunk.text)
                        }
                        is StreamChunk.ProviderFailed -> _state.update {
                            it.copy(notice = "${chunk.providerName} failed: ${chunk.message}")
                        }
                        is StreamChunk.Failed -> _state.update { it.copy(notice = chunk.message) }
                        StreamChunk.Done -> Unit
                    }
                }
        }
    }

    fun stop() {
        sendJob?.cancel()
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun cycleUnrestrictedMode() {
        val conv = _state.value.conversation ?: return
        val modes = UnrestrictedMode.entries
        save(conv.copy(unrestrictedMode = modes[(conv.unrestrictedMode.ordinal + 1) % modes.size]))
    }

    fun setRoutingStrategy(strategy: RoutingStrategy) {
        val conv = _state.value.conversation ?: return
        save(conv.copy(routingStrategy = strategy))
    }

    private fun save(conversation: Conversation) {
        _state.update { it.copy(conversation = conversation) }
        viewModelScope.launch { conversationRepository.save(conversation) }
    }
}
