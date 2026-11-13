package com.hermes.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.domain.model.AgentSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository
) : ViewModel() {

    // Held locally so text fields update synchronously instead of round-tripping through DataStore.
    private val _settings = MutableStateFlow<AgentSettings?>(null)
    val settings: StateFlow<AgentSettings?> = _settings.asStateFlow()

    init {
        viewModelScope.launch { _settings.value = repository.settings.first() }
    }

    fun update(transform: (AgentSettings) -> AgentSettings) {
        val next = transform(_settings.value ?: return)
        _settings.value = next
        viewModelScope.launch { repository.update(next) }
    }
}
