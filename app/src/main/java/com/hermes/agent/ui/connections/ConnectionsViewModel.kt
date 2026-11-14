package com.hermes.agent.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.connections.Connection
import com.hermes.agent.data.connections.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionsViewModel @Inject constructor(private val repository: ConnectionRepository) : ViewModel() {
    val connections: StateFlow<List<Connection>> = repository.connections

    /** IDs with a load or sign-in in progress. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

    private fun working(id: String, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.update { it + id }
        try { block() } catch (e: Exception) { _error.value = e.message ?: "Something went wrong." } finally { _busy.update { it - id } }
    }

    /** Saves, closes the editor, then loads tools unless a sign-in has to come first. */
    fun save(c: Connection, onSaved: () -> Unit) = viewModelScope.launch {
        val saved = try { repository.save(c) } catch (e: Exception) { _error.value = e.message; return@launch }
        onSaved()
        if (saved.auth != com.hermes.agent.data.connections.AuthType.OAUTH || saved.oauth.signedIn) working(saved.id) { repository.refresh(saved.id) }
    }

    fun refresh(id: String) = working(id) { repository.refresh(id) }
    fun signIn(id: String, open: (String) -> Unit) = working(id) { open(repository.startSignIn(id)) }
    fun signOut(id: String) = repository.signOut(id)
    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }
    fun setEnabled(c: Connection, on: Boolean) = viewModelScope.launch { runCatching { repository.save(c.copy(enabled = on)) } }
    fun setToolEnabled(id: String, tool: String, on: Boolean) = repository.setToolEnabled(id, tool, on)
}
