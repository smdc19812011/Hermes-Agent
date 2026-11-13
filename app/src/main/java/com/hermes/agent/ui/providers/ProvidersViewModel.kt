package com.hermes.agent.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.domain.model.Provider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val repository: ProviderRepository
) : ViewModel() {

    val providers: StateFlow<List<Provider>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteProvider(provider: Provider) {
        viewModelScope.launch { repository.delete(provider) }
    }

    fun toggleEnabled(provider: Provider) {
        viewModelScope.launch {
            repository.save(provider.copy(isEnabled = !provider.isEnabled))
        }
    }

    fun movePriority(provider: Provider, up: Boolean) {
        viewModelScope.launch {
            val all = providers.value.toMutableList()
            val idx = all.indexOfFirst { it.id == provider.id }
            val swapIdx = if (up) idx - 1 else idx + 1
            if (idx < 0 || swapIdx !in all.indices) return@launch
            all[idx] = all[swapIdx].also { all[swapIdx] = all[idx] }
            // Rewrite as 0..n-1 so duplicate or gapped priorities can't break ordering.
            all.forEachIndexed { i, p -> if (p.priority != i) repository.save(p.copy(priority = i)) }
        }
    }
}
