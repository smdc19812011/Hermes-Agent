package com.hermes.agent.ui.chat

import com.hermes.agent.data.repository.ConversationRepository
import com.hermes.agent.data.repository.LLMRepository
import com.hermes.agent.data.repository.OpenRouterUsageRepository
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.data.tools.ToolApprovals
import com.hermes.agent.domain.model.Attachment
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.model.StreamChunk
import com.hermes.agent.domain.model.ToolActivity
import com.hermes.agent.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns each conversation's live chat state — the in-flight generation, its task-panel history, a pending approval —
 * independent of any one [ChatViewModel] instance. Compose Navigation destroys and recreates the ViewModel every
 * time the chat screen is left and reopened (even for the same conversation), which used to cancel the running
 * message and wipe its task-panel history with it. Kept here instead, this survives that: a long job (building an
 * APK, say) keeps running, and its activity log is exactly as the user left it when they come back.
 */
@Singleton
class ChatRunner @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    private val providerRepository: ProviderRepository,
    private val llmRepository: LLMRepository,
    private val toolApprovals: ToolApprovals,
    private val openRouterUsageRepository: OpenRouterUsageRepository,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val states = ConcurrentHashMap<String, MutableStateFlow<ChatUiState>>()
    private val sendJobs = ConcurrentHashMap<String, Job>()
    private val observing = ConcurrentHashMap.newKeySet<String>()

    fun stateFor(conversationId: String): MutableStateFlow<ChatUiState> =
        states.getOrPut(conversationId) { MutableStateFlow(ChatUiState()) }

    /** Starts this conversation's Room observers once; a later call for the same id (a ViewModel recreated) is a no-op. */
    fun ensureObserving(conversationId: String) {
        if (!observing.add(conversationId)) return
        val state = stateFor(conversationId)
        conversationRepository.observeById(conversationId).onEach { conv -> state.update { it.copy(conversation = conv) } }.launchIn(scope)
        conversationRepository.observeMessages(conversationId).onEach { msgs -> state.update { it.copy(messages = msgs) } }.launchIn(scope)
        settingsRepository.settings.onEach { s -> state.update { it.copy(showProviderBadge = s.showProviderBadge) } }.launchIn(scope)
    }

    fun send(conversationId: String, input: String, files: List<Attachment>) {
        val state = stateFor(conversationId)
        val conversation = state.value.conversation ?: return
        if (state.value.isLoading || state.value.importing || (input.isBlank() && files.isEmpty())) return
        val history = state.value.messages

        state.update { it.copy(isLoading = true, streamingContent = "", tools = emptyList(), approval = null, notice = null, streamingFiles = emptyList(), pending = emptyList()) }

        sendJobs[conversationId] = scope.launch {
            sendMessageUseCase(conversation, input.trim(), history, files)
                .catch { e -> state.update { it.copy(notice = e.message ?: "Request failed", failedAt = System.currentTimeMillis()) } }
                .onCompletion {
                    state.update { it.copy(isLoading = false, streamingContent = "", tools = emptyList(), approval = null, streamingFiles = emptyList(), waitingUntil = 0) }
                }
                .collect { chunk ->
                    when (chunk) {
                        is StreamChunk.ProviderSelected -> {
                            state.update {
                                it.copy(
                                    activeProviderId = chunk.providerId,
                                    activeProviderName = chunk.providerName,
                                    activeModel = chunk.model,
                                    streamingContent = "",
                                    tools = emptyList(),
                                    waitingUntil = 0
                                )
                            }
                            refreshOpenRouterUsage(conversationId, chunk.providerId)
                        }
                        is StreamChunk.Token -> state.update {
                            it.copy(streamingContent = it.streamingContent + chunk.text, lastTokenAt = System.currentTimeMillis(), waitingUntil = 0)
                        }
                        is StreamChunk.Waiting -> state.update { it.copy(waitingUntil = chunk.untilMs, waitingLabel = chunk.label) }
                        is StreamChunk.Tool -> state.update { s ->
                            val a = chunk.activity
                            // A "completed" frame omits the label, so keep the running frame's label and only add fresh fields.
                            fun merge(list: List<ToolActivity>) = if (list.any { it.id == a.id })
                                list.map { if (it.id == a.id) it.copy(completed = a.completed, output = a.output.ifEmpty { it.output }, failed = it.failed || a.failed) else it }
                            else list + a
                            s.copy(tools = merge(s.tools), panelTasks = merge(s.panelTasks))
                        }
                        is StreamChunk.ApprovalRequested -> state.update {
                            it.copy(approval = PendingApproval(chunk.providerId, chunk.request))
                        }
                        is StreamChunk.ProviderFailed -> state.update {
                            it.copy(notice = "${chunk.providerName} failed: ${chunk.message}")
                        }
                        is StreamChunk.Failed -> state.update { it.copy(notice = chunk.message, failedAt = System.currentTimeMillis()) }
                        is StreamChunk.Notice -> state.update { it.copy(notice = chunk.message) }
                        is StreamChunk.File -> state.update { it.copy(streamingFiles = it.streamingFiles + chunk.attachment) }
                        StreamChunk.Done -> Unit
                    }
                }
        }
    }

    /** Pulls OpenRouter's own usage/rate-limit report for this provider (a no-op, returning null, for any other type). */
    private fun refreshOpenRouterUsage(conversationId: String, providerId: String) {
        scope.launch {
            val provider = providerRepository.getById(providerId) ?: return@launch
            val usage = runCatching { openRouterUsageRepository.usage(provider) }.getOrNull()
            val state = stateFor(conversationId)
            if (state.value.activeProviderId == providerId) state.update { it.copy(openRouterUsage = usage) }
        }
    }

    fun stop(conversationId: String) {
        sendJobs[conversationId]?.cancel()
    }

    fun answerApproval(conversationId: String, choice: String) {
        val state = stateFor(conversationId)
        val pending = state.value.approval ?: return
        if (pending.sending) return
        if (pending.request.local) {
            toolApprovals.answer(pending.request.runId, choice)
            state.update { it.copy(approval = null) }
            return
        }
        state.update { it.copy(approval = pending.copy(sending = true)) }
        scope.launch {
            val provider = providerRepository.getById(pending.providerId)
            val error = runCatching {
                requireNotNull(provider) { "Provider no longer exists" }
                llmRepository.answerApproval(provider, pending.request.runId, choice)
            }.exceptionOrNull()
            state.update {
                if (error == null) it.copy(approval = null)
                else it.copy(approval = pending, notice = "Couldn't send your answer: ${error.message}")
            }
        }
    }

    fun save(conversationId: String, conversation: Conversation) {
        stateFor(conversationId).update { it.copy(conversation = conversation) }
        scope.launch { conversationRepository.save(conversation) }
    }
}
