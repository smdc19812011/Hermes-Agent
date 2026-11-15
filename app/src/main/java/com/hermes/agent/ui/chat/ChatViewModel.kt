package com.hermes.agent.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.OpenRouterUsage
import com.hermes.agent.data.repository.RequestCounts
import com.hermes.agent.data.repository.RequestThrottle
import com.hermes.agent.data.sandbox.SandboxLanguage
import com.hermes.agent.data.sandbox.SandboxResult
import com.hermes.agent.data.sandbox.SandboxRunner
import com.hermes.agent.domain.model.*
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
    val activeProviderId: String? = null,
    val activeProviderName: String? = null,
    val activeModel: String? = null,
    /** OpenRouter's own report for the active provider's key, refreshed periodically; null off-OpenRouter or not yet loaded. */
    val openRouterUsage: OpenRouterUsage? = null,
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
    val failedAt: Long = 0,
    /** Every tool run in this chat view, newest last, for the task panel; kept across messages. */
    val panelTasks: List<ToolActivity> = emptyList(),
    val panelOpen: Boolean = false,
    /** >0 while a request is being deliberately held back (rate-limit pacing or a 429 retry), for the avatar's countdown. */
    val waitingUntil: Long = 0,
    val waitingLabel: String = ""
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val runner: ChatRunner,
    private val sandboxRunner: SandboxRunner,
    private val attachmentStore: com.hermes.agent.data.media.AttachmentStore,
    private val requestThrottle: RequestThrottle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    agentLibrary: com.hermes.agent.data.agents.AgentLibraryRepository
) : ViewModel() {

    // Compose Navigation gives every chat destination a fresh ViewModel, even for the same conversation — the
    // actual state lives in ChatRunner, keyed by this id, so a running message and its task-panel history survive that.
    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"]) { "ChatViewModel needs a conversationId" }
    private val sharedState = runner.stateFor(conversationId)

    /** Saved agents this chat can switch to. */
    val agents: StateFlow<List<com.hermes.agent.domain.model.AgentProfile>> = agentLibrary.agents

    val state: StateFlow<ChatUiState> = sharedState.asStateFlow()

    private var codeJob: Job? = null

    init {
        runner.ensureObserving(conversationId)
        sharedState.update { it.copy(termuxReady = sandboxRunner.termuxReady()) }
    }

    fun setPanelOpen(open: Boolean) = sharedState.update { it.copy(panelOpen = open) }
    fun clearPanel() = sharedState.update { it.copy(panelTasks = emptyList()) }

    fun refreshTermux() = sharedState.update { it.copy(termuxReady = sandboxRunner.termuxReady()) }

    /** How many requests this app has sent [providerId] today and in the last minute; cheap and synchronous for the UI to poll. */
    fun requestStats(providerId: String, windowMs: Long = 60_000L): RequestCounts = requestThrottle.counts(providerId, windowMs = windowMs)

    fun send(input: String) = runner.send(conversationId, input, sharedState.value.pending)

    fun answerApproval(choice: String) = runner.answerApproval(conversationId, choice)

    /** A local copy for saving or sharing; remote previews are downloaded first. */
    private suspend fun local(a: Attachment): Attachment {
        if (!a.isRemote) return a
        return attachmentStore.download(conversationId, a.uri, a.name.takeIf { '.' in it }, a.caption)
    }

    fun saveAttachment(a: Attachment, target: android.net.Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val file = attachmentStore.file(local(a))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
            }
            sharedState.update { it.copy(notice = result.fold({ "Saved ${a.name}." }, { e -> "Couldn't save ${a.name}: ${e.message}" })) }
        }
    }

    /** Resolves a shareable content:// URI, then hands it to [onReady] on the main thread. */
    fun shareAttachment(a: Attachment, onReady: (android.net.Uri, String) -> Unit) {
        viewModelScope.launch {
            runCatching { local(a) }
                .onSuccess { file -> onReady(attachmentStore.shareUri(file), file.mime.takeIf { '*' !in it } ?: "application/octet-stream") }
                .onFailure { e -> sharedState.update { it.copy(notice = "Couldn't share ${a.name}: ${e.message}") } }
        }
    }

    fun fileOf(a: Attachment): java.io.File = attachmentStore.file(a)

    fun attach(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        sharedState.update { it.copy(importing = true) }
        viewModelScope.launch {
            val added = mutableListOf<Attachment>()
            var failed: String? = null
            uris.take(10).forEach { uri ->
                runCatching { attachmentStore.importUri(conversationId, uri) }.onSuccess { added += it }.onFailure { failed = it.message }
            }
            sharedState.update { it.copy(importing = false, pending = it.pending + added, notice = failed?.let { m -> "Couldn't attach a file: $m" } ?: it.notice) }
        }
    }

    fun cameraTarget(): Pair<java.io.File, android.net.Uri> = attachmentStore.cameraTarget()

    /** The camera wrote into [file]; move it into the chat like any other pick. */
    fun attachPhoto(file: java.io.File, taken: Boolean) {
        if (!taken || !file.exists() || file.length() == 0L) { file.delete(); return }
        viewModelScope.launch {
            val a = runCatching { file.inputStream().use { attachmentStore.saveStream(conversationId, file.name, it, "image/jpeg") } }.getOrNull()
            file.delete()
            a?.let { att -> sharedState.update { it.copy(pending = it.pending + att) } }
        }
    }

    fun removePending(a: Attachment) {
        sharedState.update { it.copy(pending = it.pending - a) }
        runCatching { attachmentStore.file(a).delete() }
    }

    fun stop() = runner.stop(conversationId)

    fun clearNotice() = sharedState.update { it.copy(notice = null) }

    fun runCode(language: SandboxLanguage, code: String) {
        codeJob?.cancel()
        sharedState.update { it.copy(codeRun = CodeRun(language, code)) }
        codeJob = viewModelScope.launch {
            val result = sandboxRunner.run(language, code)
            sharedState.update { s -> s.codeRun?.takeIf { it.code == code }?.let { s.copy(codeRun = it.copy(result = result)) } ?: s }
        }
    }

    fun dismissCodeRun() {
        codeJob?.cancel()
        sharedState.update { it.copy(codeRun = null) }
    }

    fun cycleUnrestrictedMode() {
        val conv = sharedState.value.conversation ?: return
        val modes = UnrestrictedMode.entries
        runner.save(conversationId, conv.copy(unrestrictedMode = modes[(conv.unrestrictedMode.ordinal + 1) % modes.size]))
    }

    fun setAgent(agentId: String?) {
        val conv = sharedState.value.conversation ?: return
        runner.save(conversationId, conv.copy(agentId = agentId))
    }

    fun setRoutingStrategy(strategy: RoutingStrategy) {
        val conv = sharedState.value.conversation ?: return
        runner.save(conversationId, conv.copy(routingStrategy = strategy))
    }
}
