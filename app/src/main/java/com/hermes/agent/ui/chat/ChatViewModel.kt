package com.hermes.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.ConversationRepository
import com.hermes.agent.data.repository.LLMRepository
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.data.sandbox.SandboxLanguage
import com.hermes.agent.data.sandbox.SandboxResult
import com.hermes.agent.data.sandbox.SandboxRunner
import com.hermes.agent.data.tools.ToolApprovals
import com.hermes.agent.domain.model.*
import com.hermes.agent.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingApproval(val providerId: String, val request: ApprovalRequest, val sending: Boolean = false)

data class CodeRun(val language: SandboxLanguage, val code: String, val result: SandboxResult? = null)

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val streamingContent: String = "",
    val tools: List<ToolActivity> = emptyList(),
    val approval: PendingApproval? = null,
    val activeProviderName: String? = null,
    val activeModel: String? = null,
    val isLoading: Boolean = false,
    val notice: String? = null,
    val showProviderBadge: Boolean = true,
    val codeRun: CodeRun? = null,
    /** Files posted during the reply being streamed. */
    val streamingFiles: List<Attachment> = emptyList(),
    /** Files picked with + and not sent yet. */
    val pending: List<Attachment> = emptyList(),
    val importing: Boolean = false,
    val termuxReady: Boolean = false,
    /** When the last token arrived and the last reply failed, for the avatar. */
    val lastTokenAt: Long = 0,
    val failedAt: Long = 0
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    private val providerRepository: ProviderRepository,
    private val llmRepository: LLMRepository,
    private val sandboxRunner: SandboxRunner,
    private val toolApprovals: ToolApprovals,
    private val attachmentStore: com.hermes.agent.data.media.AttachmentStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    settingsRepository: SettingsRepository,
    agentLibrary: com.hermes.agent.data.agents.AgentLibraryRepository
) : ViewModel() {

    /** Saved agents this chat can switch to. */
    val agents: StateFlow<List<com.hermes.agent.domain.model.AgentProfile>> = agentLibrary.agents

    private val _state = MutableStateFlow(ChatUiState(termuxReady = sandboxRunner.termuxReady()))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var initializedFor: String? = null
    private var sendJob: Job? = null
    private var codeJob: Job? = null

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

    fun refreshTermux() = _state.update { it.copy(termuxReady = sandboxRunner.termuxReady()) }

    fun send(input: String) {
        val conversation = _state.value.conversation ?: return
        val files = _state.value.pending
        if (_state.value.isLoading || _state.value.importing || (input.isBlank() && files.isEmpty())) return

        _state.update { it.copy(isLoading = true, streamingContent = "", tools = emptyList(), approval = null, notice = null, streamingFiles = emptyList(), pending = emptyList()) }
        val history = _state.value.messages

        sendJob = viewModelScope.launch {
            sendMessageUseCase(conversation, input.trim(), history, files)
                .catch { e -> _state.update { it.copy(notice = e.message ?: "Request failed", failedAt = System.currentTimeMillis()) } }
                .onCompletion {
                    _state.update { it.copy(isLoading = false, streamingContent = "", tools = emptyList(), approval = null, streamingFiles = emptyList()) }
                }
                .collect { chunk ->
                    when (chunk) {
                        is StreamChunk.ProviderSelected -> _state.update {
                            it.copy(
                                activeProviderName = chunk.providerName,
                                activeModel = chunk.model,
                                streamingContent = "",
                                tools = emptyList()
                            )
                        }
                        is StreamChunk.Token -> _state.update {
                            it.copy(streamingContent = it.streamingContent + chunk.text, lastTokenAt = System.currentTimeMillis())
                        }
                        is StreamChunk.Tool -> _state.update { s ->
                            val a = chunk.activity
                            // A "completed" frame omits the label, so keep the running frame's label.
                            val tools = if (s.tools.any { it.id == a.id }) {
                                s.tools.map { if (it.id == a.id) it.copy(completed = a.completed) else it }
                            } else s.tools + a
                            s.copy(tools = tools)
                        }
                        is StreamChunk.ApprovalRequested -> _state.update {
                            it.copy(approval = PendingApproval(chunk.providerId, chunk.request))
                        }
                        is StreamChunk.ProviderFailed -> _state.update {
                            it.copy(notice = "${chunk.providerName} failed: ${chunk.message}")
                        }
                        is StreamChunk.Failed -> _state.update { it.copy(notice = chunk.message, failedAt = System.currentTimeMillis()) }
                        is StreamChunk.Notice -> _state.update { it.copy(notice = chunk.message) }
                        is StreamChunk.File -> _state.update { it.copy(streamingFiles = it.streamingFiles + chunk.attachment) }
                        StreamChunk.Done -> Unit
                    }
                }
        }
    }

    fun answerApproval(choice: String) {
        val pending = _state.value.approval ?: return
        if (pending.sending) return
        if (pending.request.local) {
            toolApprovals.answer(pending.request.runId, choice)
            _state.update { it.copy(approval = null) }
            return
        }
        _state.update { it.copy(approval = pending.copy(sending = true)) }
        viewModelScope.launch {
            val provider = providerRepository.getById(pending.providerId)
            val error = runCatching {
                requireNotNull(provider) { "Provider no longer exists" }
                llmRepository.answerApproval(provider, pending.request.runId, choice)
            }.exceptionOrNull()
            _state.update {
                if (error == null) it.copy(approval = null)
                else it.copy(approval = pending, notice = "Couldn't send your answer: ${error.message}")
            }
        }
    }

    /** A local copy for saving or sharing; remote previews are downloaded first. */
    private suspend fun local(a: Attachment): Attachment {
        if (!a.isRemote) return a
        val conv = _state.value.conversation?.id ?: "shared"
        return attachmentStore.download(conv, a.uri, a.name.takeIf { '.' in it }, a.caption)
    }

    fun saveAttachment(a: Attachment, target: android.net.Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val file = attachmentStore.file(local(a))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
            }
            _state.update { it.copy(notice = result.fold({ "Saved ${a.name}." }, { e -> "Couldn't save ${a.name}: ${e.message}" })) }
        }
    }

    /** Resolves a shareable content:// URI, then hands it to [onReady] on the main thread. */
    fun shareAttachment(a: Attachment, onReady: (android.net.Uri, String) -> Unit) {
        viewModelScope.launch {
            runCatching { local(a) }
                .onSuccess { file -> onReady(attachmentStore.shareUri(file), file.mime.takeIf { '*' !in it } ?: "application/octet-stream") }
                .onFailure { e -> _state.update { it.copy(notice = "Couldn't share ${a.name}: ${e.message}") } }
        }
    }

    fun fileOf(a: Attachment): java.io.File = attachmentStore.file(a)

    fun attach(uris: List<android.net.Uri>) {
        val conv = _state.value.conversation?.id ?: return
        if (uris.isEmpty()) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            val added = mutableListOf<Attachment>()
            var failed: String? = null
            uris.take(10).forEach { uri ->
                runCatching { attachmentStore.importUri(conv, uri) }.onSuccess { added += it }.onFailure { failed = it.message }
            }
            _state.update { it.copy(importing = false, pending = it.pending + added, notice = failed?.let { m -> "Couldn't attach a file: $m" } ?: it.notice) }
        }
    }

    fun cameraTarget(): Pair<java.io.File, android.net.Uri> = attachmentStore.cameraTarget()

    /** The camera wrote into [file]; move it into the chat like any other pick. */
    fun attachPhoto(file: java.io.File, taken: Boolean) {
        if (!taken || !file.exists() || file.length() == 0L) { file.delete(); return }
        val conv = _state.value.conversation?.id ?: return
        viewModelScope.launch {
            val a = runCatching { file.inputStream().use { attachmentStore.saveStream(conv, file.name, it, "image/jpeg") } }.getOrNull()
            file.delete()
            a?.let { att -> _state.update { it.copy(pending = it.pending + att) } }
        }
    }

    fun removePending(a: Attachment) {
        _state.update { it.copy(pending = it.pending - a) }
        runCatching { attachmentStore.file(a).delete() }
    }

    fun stop() {
        sendJob?.cancel()
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun runCode(language: SandboxLanguage, code: String) {
        codeJob?.cancel()
        _state.update { it.copy(codeRun = CodeRun(language, code)) }
        codeJob = viewModelScope.launch {
            val result = sandboxRunner.run(language, code)
            _state.update { s -> s.codeRun?.takeIf { it.code == code }?.let { s.copy(codeRun = it.copy(result = result)) } ?: s }
        }
    }

    fun dismissCodeRun() {
        codeJob?.cancel()
        _state.update { it.copy(codeRun = null) }
    }

    fun cycleUnrestrictedMode() {
        val conv = _state.value.conversation ?: return
        val modes = UnrestrictedMode.entries
        save(conv.copy(unrestrictedMode = modes[(conv.unrestrictedMode.ordinal + 1) % modes.size]))
    }

    fun setAgent(agentId: String?) {
        val conv = _state.value.conversation ?: return
        save(conv.copy(agentId = agentId))
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
