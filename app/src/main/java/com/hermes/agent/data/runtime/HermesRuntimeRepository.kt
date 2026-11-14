package com.hermes.agent.data.runtime

import android.content.Context
import androidx.annotation.Keep
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.data.termux.CommandResult
import com.hermes.agent.data.termux.TermuxBridge
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// Persisted as JSON via Gson: names must survive R8.
@Keep
enum class RuntimeLocation { THIS_PHONE, REMOTE }

@Keep
enum class InstallMethod(val displayName: String, val description: String) {
    TERMUX_PACKAGE(
        "Official Termux package",
        "NousResearch's signed package. Smallest install, but its docs currently report it as broken."
    ),
    UBUNTU_PROOT(
        "Ubuntu inside Termux",
        "Runs the standard Linux installer in an Ubuntu userspace (proot). About 1 GB and slower; works around the broken package."
    )
}

@Keep
enum class SandboxBackend(val displayName: String, val description: String, val hermesValue: String) {
    LOCAL("This phone", "Commands run inside Termux on this phone.", "local"),
    SSH("SSH server", "Commands run on a machine you reach over SSH.", "ssh"),
    DOCKER("Remote Docker host", "Commands run in containers on a Docker daemon reached over SSH or TCP. Android can't run a Docker daemon itself.", "docker"),
    MODAL("Modal", "Commands run in Modal cloud sandboxes.", "modal"),
    DAYTONA("Daytona", "Commands run in Daytona cloud sandboxes.", "daytona")
}

@Keep
data class RuntimeSettings(
    val location: RuntimeLocation = RuntimeLocation.THIS_PHONE,
    val installMethod: InstallMethod = InstallMethod.TERMUX_PACKAGE,
    val serverKey: String = "",
    val remoteUrl: String = "",
    val remoteKey: String = "",
    val modelProviderId: String = "",
    /** True once "Save configuration" succeeded, i.e. Hermes has a model and backend set. */
    val configured: Boolean = false,
    val backend: SandboxBackend = SandboxBackend.LOCAL,
    val sshHost: String = "",
    val sshUser: String = "",
    val sshPort: String = "22",
    val sshKeyPath: String = "~/.ssh/id_ed25519",
    val dockerHost: String = "",
    val dockerImage: String = "python:3.12-slim",
    val modalTokenId: String = "",
    val modalTokenSecret: String = "",
    val daytonaApiKey: String = ""
)

sealed class ServerStatus {
    data object Unknown : ServerStatus()
    data object Offline : ServerStatus()
    data class Online(val detail: String) : ServerStatus()
    data class Error(val message: String) : ServerStatus()
}

private val Context.runtimeStore: DataStore<Preferences> by preferencesDataStore("hermes_runtime")

@Singleton
class HermesRuntimeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termux: TermuxBridge,
    private val providerRepository: ProviderRepository,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private val key = stringPreferencesKey("runtime_settings")

    val settings: Flow<RuntimeSettings> = context.runtimeStore.data.map { prefs ->
        prefs[key]?.let { runCatching { gson.fromJson(it, RuntimeSettings::class.java) }.getOrNull() }
            ?: RuntimeSettings()
    }

    suspend fun save(settings: RuntimeSettings) {
        context.runtimeStore.edit { it[key] = gson.toJson(settings) }
    }

    /** Returns settings with a generated server key, persisting it on first use. */
    suspend fun current(): RuntimeSettings {
        val s = settings.first()
        if (s.serverKey.isNotBlank()) return s
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val withKey = s.copy(serverKey = "hk_" + bytes.joinToString("") { "%02x".format(it) })
        save(withKey)
        return withKey
    }

    fun baseUrl(s: RuntimeSettings): String = when (s.location) {
        RuntimeLocation.THIS_PHONE -> "http://127.0.0.1:${HermesScripts.LOCAL_PORT}"
        RuntimeLocation.REMOTE -> s.remoteUrl.trim().trimEnd('/').removeSuffix("/v1")
    }

    private fun apiKey(s: RuntimeSettings) =
        if (s.location == RuntimeLocation.THIS_PHONE) s.serverKey else s.remoteKey.trim()

    suspend fun checkHealth(s: RuntimeSettings): ServerStatus = withContext(Dispatchers.IO) {
        val base = baseUrl(s)
        if (base.isBlank()) return@withContext ServerStatus.Unknown
        val client = okHttpClient.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("$base/health")
            .apply { apiKey(s).takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .build()
        try {
            client.newCall(request).execute().use { r ->
                if (r.isSuccessful) ServerStatus.Online(r.body?.string().orEmpty().take(120))
                else ServerStatus.Error("HTTP ${r.code}")
            }
        } catch (e: Exception) {
            if (s.location == RuntimeLocation.THIS_PHONE) ServerStatus.Offline
            else ServerStatus.Error(e.message ?: "unreachable")
        }
    }

    /** Creates or updates the Hermes provider the chat routes through, at top priority. */
    suspend fun ensureProvider(s: RuntimeSettings): Provider {
        val name = if (s.location == RuntimeLocation.THIS_PHONE) "Hermes (this phone)" else "Hermes (remote)"
        val all = providerRepository.getAll()
        val existing = all.firstOrNull { it.isHermes && it.name == name }
        val provider = Provider(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            type = ProviderType.HERMES,
            apiKey = apiKey(s),
            baseUrl = "${baseUrl(s)}/v1",
            models = existing?.models?.takeIf { it.isNotEmpty() } ?: listOf("hermes-agent"),
            isEnabled = true,
            priority = existing?.priority ?: ((all.minOfOrNull { it.priority } ?: 0) - 1),
            avgResponseMs = existing?.avgResponseMs ?: 0L
        )
        providerRepository.save(provider)
        return provider
    }

    suspend fun install(s: RuntimeSettings): CommandResult = termux.run(
        HermesScripts.logged("install.log", HermesScripts.install(s.installMethod)),
        "Install Hermes Agent",
        timeoutMs = 45 * 60_000L
    )

    suspend fun configure(s: RuntimeSettings): CommandResult = termux.run(
        HermesScripts.logged("configure.log", HermesScripts.configure(s, providerRepository.getById(s.modelProviderId))),
        "Configure Hermes Agent",
        timeoutMs = 15 * 60_000L
    )

    fun start(s: RuntimeSettings) = termux.launch(HermesScripts.start(s.installMethod, s.serverKey), "Hermes gateway")

    suspend fun stop(): CommandResult = termux.run(HermesScripts.stop(), "Stop Hermes gateway", 30_000)

    suspend fun readLog(name: String, lines: Int = 60): String =
        termux.run(HermesScripts.readLog(name, lines), "Read Hermes log", 20_000).let { it.termuxError ?: it.stdout }

    suspend fun detectInstall(s: RuntimeSettings): String? {
        val r = termux.run(HermesScripts.detect(s.installMethod), "Check Hermes install", 90_000)
        return if (r.ok) r.stdout.trim().ifBlank { "installed" } else null
    }
}
