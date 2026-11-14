package com.hermes.agent.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.agent.domain.model.*
import com.hermes.agent.domain.tools.Capability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("hermes_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val GLOBAL_SYSTEM_PROMPT = stringPreferencesKey("global_system_prompt")
        val ROUTING_STRATEGY = stringPreferencesKey("routing_strategy")
        val UNRESTRICTED_MODE = stringPreferencesKey("unrestricted_mode")
        val CUSTOM_UNRESTRICTED_PROMPT = stringPreferencesKey("custom_unrestricted_prompt")
        val MAX_PROVIDER_ATTEMPTS = intPreferencesKey("max_provider_attempts")
        val REQUEST_TIMEOUT = intPreferencesKey("request_timeout_seconds")
        val SHOW_PROVIDER_BADGE = booleanPreferencesKey("show_provider_badge")
        val THEME = stringPreferencesKey("theme")
        val DEVICE_ACCESS = stringSetPreferencesKey("device_access")
        val IMAGE_PROVIDER = stringPreferencesKey("image_provider")
        val IMAGE_MODEL = stringPreferencesKey("image_model")
        val TTS_PROVIDER = stringPreferencesKey("tts_provider")
        val TTS_MODEL = stringPreferencesKey("tts_model")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
    }

    val settings: Flow<AgentSettings> = context.dataStore.data.map { prefs ->
        val defaults = AgentSettings()
        AgentSettings(
            globalSystemPrompt = prefs[Keys.GLOBAL_SYSTEM_PROMPT] ?: defaults.globalSystemPrompt,
            globalRoutingStrategy = enumOr(prefs[Keys.ROUTING_STRATEGY], defaults.globalRoutingStrategy),
            globalUnrestrictedMode = enumOr(prefs[Keys.UNRESTRICTED_MODE], defaults.globalUnrestrictedMode),
            customUnrestrictedPrompt = prefs[Keys.CUSTOM_UNRESTRICTED_PROMPT] ?: defaults.customUnrestrictedPrompt,
            maxProviderAttempts = prefs[Keys.MAX_PROVIDER_ATTEMPTS] ?: defaults.maxProviderAttempts,
            requestTimeoutSeconds = prefs[Keys.REQUEST_TIMEOUT] ?: defaults.requestTimeoutSeconds,
            showProviderBadge = prefs[Keys.SHOW_PROVIDER_BADGE] ?: defaults.showProviderBadge,
            theme = enumOr(prefs[Keys.THEME], defaults.theme),
            deviceAccess = prefs[Keys.DEVICE_ACCESS].orEmpty()
                .mapNotNull { runCatching { Capability.valueOf(it) }.getOrNull() }.toSet(),
            imageProviderId = prefs[Keys.IMAGE_PROVIDER].orEmpty(),
            imageModel = prefs[Keys.IMAGE_MODEL].orEmpty(),
            ttsProviderId = prefs[Keys.TTS_PROVIDER].orEmpty(),
            ttsModel = prefs[Keys.TTS_MODEL].orEmpty(),
            ttsVoice = prefs[Keys.TTS_VOICE].orEmpty()
        )
    }

    suspend fun update(settings: AgentSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GLOBAL_SYSTEM_PROMPT] = settings.globalSystemPrompt
            prefs[Keys.ROUTING_STRATEGY] = settings.globalRoutingStrategy.name
            prefs[Keys.UNRESTRICTED_MODE] = settings.globalUnrestrictedMode.name
            prefs[Keys.CUSTOM_UNRESTRICTED_PROMPT] = settings.customUnrestrictedPrompt
            prefs[Keys.MAX_PROVIDER_ATTEMPTS] = settings.maxProviderAttempts
            prefs[Keys.REQUEST_TIMEOUT] = settings.requestTimeoutSeconds
            prefs[Keys.SHOW_PROVIDER_BADGE] = settings.showProviderBadge
            prefs[Keys.THEME] = settings.theme.name
            prefs[Keys.DEVICE_ACCESS] = settings.deviceAccess.map { it.name }.toSet()
            prefs[Keys.IMAGE_PROVIDER] = settings.imageProviderId
            prefs[Keys.IMAGE_MODEL] = settings.imageModel
            prefs[Keys.TTS_PROVIDER] = settings.ttsProviderId
            prefs[Keys.TTS_MODEL] = settings.ttsModel
            prefs[Keys.TTS_VOICE] = settings.ttsVoice
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
