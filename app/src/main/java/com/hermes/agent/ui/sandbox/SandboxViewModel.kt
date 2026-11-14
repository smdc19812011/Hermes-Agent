package com.hermes.agent.ui.sandbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.sandbox.SandboxLanguage
import com.hermes.agent.data.sandbox.SandboxResult
import com.hermes.agent.data.sandbox.SandboxRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SandboxUiState(
    val language: SandboxLanguage = SandboxLanguage.PYTHON,
    val code: String = SAMPLES.getValue(SandboxLanguage.PYTHON),
    val running: Boolean = false,
    val result: SandboxResult? = null,
    val termuxReady: Boolean = false
)

private val SAMPLES = mapOf(
    SandboxLanguage.PYTHON to "import platform, sys\nprint('Python', sys.version.split()[0], 'on', platform.machine())\nprint(sum(i * i for i in range(10)))",
    SandboxLanguage.JAVASCRIPT to "console.log('Node', process.version, process.arch)\nconsole.log([1, 2, 3].map(n => n * n))",
    SandboxLanguage.BASH to "uname -a\nls \$HOME | head",
    SandboxLanguage.JAVASCRIPT_IN_APP to "const squares = [1, 2, 3].map(n => n * n)\nconsole.log('squares', squares)\nsquares.reduce((a, b) => a + b)"
)

@HiltViewModel
class SandboxViewModel @Inject constructor(
    private val runner: SandboxRunner
) : ViewModel() {

    private val _state = MutableStateFlow(SandboxUiState(termuxReady = runner.termuxReady()))
    val state: StateFlow<SandboxUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun refresh() = _state.update { it.copy(termuxReady = runner.termuxReady()) }

    fun setLanguage(language: SandboxLanguage) = _state.update {
        // Swap in the sample only if the editor still holds the previous sample.
        val keepCode = it.code.isNotBlank() && it.code != SAMPLES[it.language]
        it.copy(language = language, code = if (keepCode) it.code else SAMPLES.getValue(language), result = null)
    }

    fun setCode(code: String) = _state.update { it.copy(code = code) }

    fun run() {
        val s = _state.value
        if (s.running || s.code.isBlank()) return
        _state.update { it.copy(running = true, result = null) }
        job = viewModelScope.launch {
            val result = runner.run(s.language, s.code)
            _state.update { it.copy(running = false, result = result) }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }
}
