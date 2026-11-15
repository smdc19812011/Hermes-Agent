package com.hermes.agent.data.toolbox

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hermes.agent.data.runtime.HermesScripts
import com.hermes.agent.data.termux.TermuxBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class InstallOutcome(val tool: LibraryTool, val ok: Boolean, val log: String)

private val Context.toolStore: DataStore<Preferences> by preferencesDataStore("tool_library")

/** Tools installed once into Termux and shared by every chat, the shell tool, the sandbox and Hermes. */
@Singleton
class ToolLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termux: TermuxBridge,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private val key = stringPreferencesKey("tools")
    private val writeLock = Mutex()
    private val installLock = Mutex()

    val tools: Flow<List<LibraryTool>> = context.toolStore.data.map { prefs ->
        prefs[key]?.let { runCatching { gson.fromJson(it, Array<LibraryTool>::class.java).toList() }.getOrNull() }.orEmpty()
    }

    suspend fun installedNames(): List<String> =
        tools.first().filter { it.status == InstallStatus.INSTALLED }.map { it.name }

    /** Adds (or re-uses) an entry for [address]; does not install. */
    suspend fun add(address: String, forced: SourceType? = null): LibraryTool {
        val parsed = ToolSource.parse(address, forced)
        return writeLock.withLock {
            val list = tools.first()
            list.firstOrNull { it.type == parsed.type && it.address == parsed.address }?.let { return@withLock it }
            val id = uniqueId(parsed.name, list.map { it.id }.toSet())
            val tool = LibraryTool(id, parsed.type, parsed.address, parsed.name, InstallStatus.PENDING, "", System.currentTimeMillis())
            save(list + tool)
            tool
        }
    }

    suspend fun install(id: String): InstallOutcome = installLock.withLock {
        val tool = tools.first().firstOrNull { it.id == id } ?: throw IllegalArgumentException("No tool with id $id")
        update(tool.copy(status = InstallStatus.INSTALLING, message = ""))
        val outcome = runCatching {
            if (!termux.isInstalled() || !termux.hasPermission()) {
                throw IllegalStateException("Termux isn't set up. Finish steps 1-3 on the Agent runtime screen.")
            }
            val url = if (tool.type == SourceType.GITHUB_RELEASE) resolveRelease(tool.address) else null
            // A GitHub repo's slug can differ from its binary's real name (e.g. "cli/cli" ships "gh"); once the
            // actual download address is known, show and run it under the name the source itself declares.
            val declared = (url ?: tool.address.takeIf { tool.type == SourceType.URL })
                ?.let { ToolSource.baseNameFromUrl(it) }?.takeIf { it.isNotBlank() }
            val effective = declared?.let { tool.copy(name = it) } ?: tool
            if (effective.name != tool.name) update(effective.copy(status = InstallStatus.INSTALLING, message = ""))
            val r = termux.run(HermesScripts.logged("tools.log", ToolboxScripts.install(effective, url)), "Install ${effective.name}", 20 * 60_000L)
            val log = (r.stdout + "\n" + r.stderr).trim()
            val ok = r.ok
            val message = when {
                r.termuxError != null -> r.termuxError
                ok -> url?.let { "Installed from ${it.substringAfterLast('/')}" } ?: "Installed"
                else -> log.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "Failed (exit ${r.exitCode})"
            }
            InstallOutcome(effective, ok, log) to message
        }.getOrElse { e -> InstallOutcome(tool, false, e.message.orEmpty()) to (e.message ?: "Install failed") }

        val (result, message) = outcome
        val saved = result.tool.copy(
            status = if (result.ok) InstallStatus.INSTALLED else InstallStatus.FAILED,
            message = message,
            updatedAt = System.currentTimeMillis()
        )
        update(saved)
        result.copy(tool = saved)
    }

    suspend fun remove(id: String): String {
        val tool = tools.first().firstOrNull { it.id == id } ?: return "Already removed."
        val note = if (tool.status == InstallStatus.INSTALLED && termux.isInstalled() && termux.hasPermission()) {
            val r = termux.run(ToolboxScripts.remove(tool), "Remove ${tool.name}", 5 * 60_000L)
            r.termuxError ?: if (r.ok) "Removed ${tool.name}." else "Removed from the library; uninstall reported: ${r.stderr.lines().lastOrNull().orEmpty()}"
        } else "Removed ${tool.name} from the library."
        writeLock.withLock { save(tools.first().filterNot { it.id == id }) }
        return note
    }

    suspend fun readLog(): String = runCatching {
        termux.run(HermesScripts.readLog("tools.log", 60), "Read tool log", 20_000).let { it.termuxError ?: it.stdout }
    }.getOrElse { it.message.orEmpty() }

    /** Asks GitHub for the latest release and picks the asset built for this phone's CPU. */
    private suspend fun resolveRelease(ownerRepo: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$ownerRepo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = okHttpClient.newCall(request).execute().use { r ->
            when (r.code) {
                200 -> r.body?.string().orEmpty()
                404 -> throw IOException("$ownerRepo has no published releases. Add it as a git repository instead.")
                403, 429 -> throw IOException("GitHub's rate limit was hit. Try again in a while.")
                else -> throw IOException("GitHub returned HTTP ${r.code}.")
            }
        }
        val assets = JsonParser.parseString(body).asJsonObject.getAsJsonArray("assets")
            ?.map { it.asJsonObject.get("name").asString to it.asJsonObject.get("browser_download_url").asString }
            .orEmpty()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val pick = ToolSource.pickAsset(assets.map { it.first }, abi)
            ?: throw IOException(
                "The latest $ownerRepo release has no file built for $abi. " +
                    "Add a specific download URL, or try the Termux package of the same name."
            )
        assets.first { it.first == pick }.second
    }

    private suspend fun update(tool: LibraryTool) = writeLock.withLock {
        save(tools.first().map { if (it.id == tool.id) tool else it })
    }

    private suspend fun save(list: List<LibraryTool>) {
        context.toolStore.edit { it[key] = gson.toJson(list.toTypedArray()) }
    }

    private fun uniqueId(name: String, taken: Set<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "tool" }
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
    }
}
