package com.hermes.agent.ui.runtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.data.runtime.HermesRuntimeRepository
import com.hermes.agent.data.runtime.RuntimeLocation
import com.hermes.agent.data.runtime.RuntimeSettings
import com.hermes.agent.data.runtime.SandboxBackend
import com.hermes.agent.data.runtime.ServerStatus
import com.hermes.agent.data.termux.TermuxBridge
import com.hermes.agent.domain.model.Provider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuntimeUiState(
    val settings: RuntimeSettings? = null,
    val termuxInstalled: Boolean = false,
    val termuxPermission: Boolean = false,
    val hermesVersion: String? = null,
    val server: ServerStatus = ServerStatus.Unknown,
    val busy: String? = null,
    val log: String = "",
    val message: String? = null,
    val llmProviders: List<Provider> = emptyList()
)

@HiltViewModel
class RuntimeViewModel @Inject constructor(
    private val runtime: HermesRuntimeRepository,
    private val termux: TermuxBridge,
    providerRepository: ProviderRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RuntimeUiState())
    val state: StateFlow<RuntimeUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(settings = runtime.current()) }
            refreshTermux()
        }
        providerRepository.observeAll()
            .onEach { all -> _state.update { it.copy(llmProviders = all.filter { p -> !p.isHermes }) } }
            .launchIn(viewModelScope)
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                _state.value.settings?.let { s -> _state.update { it.copy(server = runtime.checkHealth(s)) } }
                delay(5_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    fun refreshTermux() {
        _state.update { it.copy(termuxInstalled = termux.isInstalled(), termuxPermission = termux.hasPermission()) }
        val s = _state.value.settings ?: return
        if (s.location == RuntimeLocation.THIS_PHONE && termux.isInstalled() && termux.hasPermission()) {
            viewModelScope.launch {
                val version = runCatching { runtime.detectInstall(s) }.getOrNull()
                _state.update { it.copy(hermesVersion = version) }
            }
        }
    }

    fun update(transform: (RuntimeSettings) -> RuntimeSettings) {
        val next = transform(_state.value.settings ?: return)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch { runtime.save(next) }
    }

    fun install() = operation("Installing Hermes. This can take 10–30 minutes.", "install.log") { s ->
        val r = runtime.install(s)
        refreshTermux()
        if (r.ok) "Hermes installed." else failure("Install failed", r.termuxError, r.exitCode)
    }

    fun configure() = operation("Writing Hermes configuration…", "configure.log") { s ->
        val missing = missingBackendFields(s)
        if (missing != null) return@operation missing
        val r = runtime.configure(s)
        if (r.ok) {
            runtime.ensureProvider(s)
            update { it.copy(configured = true) }
            val running = runtime.checkHealth(s) is ServerStatus.Online
            "Configured. Hermes was added to Providers at top priority." +
                if (running) " Restart the gateway to apply." else " Start the gateway to use it."
        } else failure("Configuration failed", r.termuxError, r.exitCode)
    }

    fun start() = operation("Starting gateway. The first start can take a minute…", "gateway.log") { s ->
        runtime.start(s)
        runtime.ensureProvider(s)
        awaitOnline(s, "Gateway is running.")
    }

    fun stop() = operation("Stopping gateway…", null) { _ ->
        runtime.stop().let { it.termuxError ?: it.stdout.trim() }
    }

    fun restart() = operation("Restarting gateway…", "gateway.log") { s ->
        runtime.stop()
        delay(1_500)
        runtime.start(s)
        awaitOnline(s, "Gateway restarted.")
    }

    /** Python starts slowly inside proot on a phone, so allow 90 s before diagnosing from the log. */
    private suspend fun awaitOnline(s: RuntimeSettings, success: String): String {
        repeat(45) {
            delay(2_000)
            val status = runtime.checkHealth(s)
            _state.update { it.copy(server = status) }
            if (status is ServerStatus.Online) {
                return if (s.configured) success
                else "$success Hermes has no model set yet: choose one in step 5 and save, or chats will fail."
            }
        }
        val log = runCatching { runtime.readLog("gateway.log", 80) }.getOrDefault("")
        return when {
            "hermes: not found" in log.substringAfterLast("starting gateway") ->
                "Hermes isn't installed where the app looks for it. Check the install method in step 4 matches how you installed it."
            "No messaging platforms enabled" in log.substringAfterLast("starting gateway") ->
                "The gateway is running but its API server is off. Tap Save configuration in step 5, then Restart."
            else -> "The gateway didn't answer within 90 seconds. Check the log below."
        }
    }

    fun connectRemote() = operation("Connecting…", null) { s ->
        if (s.remoteUrl.isBlank()) return@operation "Enter the server's address first."
        when (val status = runtime.checkHealth(s)) {
            is ServerStatus.Online -> {
                runtime.ensureProvider(s)
                _state.update { it.copy(server = status) }
                "Connected. Hermes (remote) was added to Providers at top priority."
            }
            is ServerStatus.Error -> "Couldn't reach the server: ${status.message}"
            else -> "Couldn't reach the server."
        }
    }

    fun showLog(name: String) = viewModelScope.launch {
        _state.update { it.copy(log = runCatching { runtime.readLog(name) }.getOrElse { e -> e.message.orEmpty() }) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun operation(label: String, logName: String?, block: suspend (RuntimeSettings) -> String) {
        val s = _state.value.settings ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = label, message = null) }
        val tail = logName?.let { name ->
            viewModelScope.launch {
                while (isActive) {
                    delay(3_000)
                    runCatching { runtime.readLog(name, 40) }.onSuccess { log -> _state.update { it.copy(log = log) } }
                }
            }
        }
        viewModelScope.launch {
            val result = runCatching { block(s) }.getOrElse { e -> e.message ?: "Something went wrong." }
            tail?.cancel()
            logName?.let { name -> runCatching { runtime.readLog(name) }.onSuccess { log -> _state.update { it.copy(log = log) } } }
            _state.update { it.copy(busy = null, message = result) }
        }
    }

    private fun failure(what: String, termuxError: String?, exitCode: Int?): String = when {
        termuxError != null && termuxError.contains("allow-external-apps", ignoreCase = true) ->
            "$what: Termux is blocking outside apps. Run the step 2 command in Termux, then try again."
        termuxError != null -> "$what: $termuxError"
        else -> "$what (exit code $exitCode). See the log below."
    }

    private fun missingBackendFields(s: RuntimeSettings): String? = when (s.backend) {
        SandboxBackend.SSH -> if (s.sshHost.isBlank() || s.sshUser.isBlank()) "SSH needs a host and a user." else null
        SandboxBackend.DOCKER -> if (s.dockerHost.isBlank()) "Remote Docker needs a host, e.g. ssh://user@server." else null
        SandboxBackend.MODAL -> if (s.modalTokenId.isBlank() || s.modalTokenSecret.isBlank()) "Modal needs a token ID and secret." else null
        SandboxBackend.DAYTONA -> if (s.daytonaApiKey.isBlank()) "Daytona needs an API key." else null
        SandboxBackend.LOCAL -> null
    }
}
