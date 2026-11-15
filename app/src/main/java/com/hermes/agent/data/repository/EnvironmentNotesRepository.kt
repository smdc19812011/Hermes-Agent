package com.hermes.agent.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.environmentNotesDataStore: DataStore<Preferences> by preferencesDataStore("hermes_environment_notes")
private val NOTES = stringPreferencesKey("notes")

/**
 * What the app has learned about this phone's own hardware/software environment from running tools in chats —
 * e.g. "aapt2 isn't available for this device's ABI" or "Gradle 8.x works, 9.x needs a newer AGP". One global
 * markdown blob, shared by every chat and project (not per-conversation), so a lesson learned in one chat saves
 * every later chat from repeating the same failed attempt. The user can clear it from Settings at any time.
 */
@Singleton
class EnvironmentNotesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val notes: Flow<String> = context.environmentNotesDataStore.data.map { it[NOTES].orEmpty() }

    suspend fun save(text: String) {
        context.environmentNotesDataStore.edit { it[NOTES] = text }
    }

    suspend fun reset() {
        context.environmentNotesDataStore.edit { it.remove(NOTES) }
    }
}
