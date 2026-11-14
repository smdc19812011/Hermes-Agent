package com.hermes.agent.data.connections

import android.content.Context
import android.net.Uri
import com.google.gson.JsonObject
import com.hermes.agent.data.media.AttachmentStore
import com.hermes.agent.data.runtime.HermesScripts
import com.hermes.agent.data.termux.TermuxBridge
import com.hermes.agent.data.toolbox.ToolboxScripts
import com.hermes.agent.domain.model.Attachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Saved connections (connections/<id>.json), their cached tool lists, sign-in, and tool calls. */
@Singleton
class ConnectionRepository @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
    private val termux: TermuxBridge,
    private val attachments: AttachmentStore
) {
    private val dir = File(context.filesDir, "connections")
    private val http = okHttpClient.newBuilder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _connections = MutableStateFlow(load())
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()

    private fun load() = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { Connection.fromJson(it.readText()) }.sortedBy { it.name.lowercase() }

    fun get(id: String): Connection? = _connections.value.firstOrNull { it.id == id }

    private fun write(c: Connection): Connection = synchronized(lock) {
        dir.mkdirs()
        File(dir, "${c.id}.json").writeText(c.toJson())
        _connections.value = load()
        c
    }

    private fun update(id: String, change: (Connection) -> Connection): Connection? = synchronized(lock) { get(id)?.let { write(change(it)) } }

    suspend fun save(c: Connection): Connection = withContext(Dispatchers.IO) {
        require(c.name.isNotBlank()) { "Give the connection a name." }
        require(_connections.value.none { it.id != c.id && it.name.equals(c.name.trim(), true) }) { "There's already a connection called ${c.name.trim()}." }
        when (c.type) {
            ConnectionType.MCP_LOCAL -> require(c.command.isNotBlank()) { "Enter the command that starts the MCP server." }
            else -> require(c.url.trim().startsWith("http://") || c.url.trim().startsWith("https://")) { "Enter an http:// or https:// address." }
        }
        val previous = get(c.id)
        val changedTarget = previous == null || previous.endpoint != c.endpoint || previous.auth != c.auth || previous.command != c.command
        clients.remove(c.id)
        write(c.copy(name = c.name.trim(), port = c.port.takeIf { it > 0 } ?: (18000 + (c.id.hashCode() and 0x3ff)),
            tools = if (changedTarget) emptyList() else c.tools))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        clients.remove(id)
        synchronized(lock) { File(dir, "$id.json").delete(); _connections.value = load() }
    }

    fun setToolEnabled(id: String, tool: String, on: Boolean) {
        update(id) { c -> c.copy(disabledTools = if (on) c.disabledTools - tool else c.disabledTools + tool) }
    }

    /** Connects and reloads the tool list; failures are kept on the connection for the UI. */
    suspend fun refresh(id: String): Connection = withContext(Dispatchers.IO) {
        val c = get(id) ?: throw IllegalArgumentException("Connection not found.")
        try {
            val tools = when (c.type) {
                ConnectionType.MCP_HTTP, ConnectionType.MCP_LOCAL -> {
                    if (c.type == ConnectionType.MCP_LOCAL) ensureLocalRunning(c)
                    mcp(c).listTools()
                }
                ConnectionType.REST -> restTools(c)
            }
            update(id) { it.copy(tools = tools, lastError = "", refreshedAt = System.currentTimeMillis()) }!!
        } catch (e: McpAuthRequired) {
            update(id) { it.copy(lastError = if (it.auth == AuthType.OAUTH) "Sign in to load its tools." else "This server needs a sign-in. Set Authentication to OAuth 2 sign-in, or add a token.") }!!
        } catch (e: Exception) {
            update(id) { it.copy(lastError = e.message ?: e.javaClass.simpleName) }!!
        }
    }

    private fun restTools(c: Connection): List<RemoteTool> {
        if (c.openApiUrl.isBlank()) return listOf(OpenApi.REQUEST_TOOL)
        val request = Request.Builder().url(c.openApiUrl.trim()).apply { headers(c).forEach { (k, v) -> header(k, v) } }.build()
        val body = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Couldn't download the OpenAPI document: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        return OpenApi.parse(body, c.openApiUrl.trim()).tools + OpenApi.REQUEST_TOOL
    }

    private fun mcp(c: Connection): McpClient = clients.getOrPut(c.id) { McpClient(http, c.endpoint) { headers(get(c.id) ?: c) } }

    /** Auth headers for [c], refreshing an OAuth token that's about to expire. */
    private fun headers(c: Connection): Map<String, String> = when (c.auth) {
        AuthType.NONE -> emptyMap()
        AuthType.BEARER -> mapOf("Authorization" to "Bearer ${c.secret.trim()}")
        AuthType.HEADER -> mapOf(c.authName.trim().ifEmpty { "X-Api-Key" } to c.secret.trim())
        AuthType.BASIC -> mapOf("Authorization" to Credentials.basic(c.authName, c.secret))
        AuthType.OAUTH -> {
            var o = c.oauth
            if (o.signedIn && o.expiresAt in 1..(System.currentTimeMillis() + 60_000) && o.refreshToken.isNotBlank()) {
                o = OAuth.refresh(http, o)
                update(c.id) { it.copy(oauth = o) }
            }
            if (o.signedIn) mapOf("Authorization" to "Bearer ${o.accessToken}") else emptyMap()
        }
    }

    // ---- OAuth ----

    /** Prepares a browser sign-in and returns the URL to open; MCP servers' endpoints are discovered when not filled in. */
    suspend fun startSignIn(id: String): String = withContext(Dispatchers.IO) {
        var c = get(id) ?: throw IllegalArgumentException("Connection not found.")
        var o = c.oauth
        if (o.authUrl.isBlank() || o.tokenUrl.isBlank() || o.clientId.isBlank()) {
            require(c.type != ConnectionType.REST || (o.authUrl.isNotBlank() && o.tokenUrl.isNotBlank() && o.clientId.isNotBlank())) {
                "Enter the API's authorization URL, token URL and client ID."
            }
            val challenge = runCatching { clients.remove(id); McpClient(http, c.endpoint) { emptyMap() }.listTools(); null }
                .exceptionOrNull() as? McpAuthRequired
            o = OAuth.discover(http, c.endpoint, challenge?.wwwAuthenticate, o)
        }
        val state = OAuth.randomToken(16)
        val verifier = OAuth.randomToken(48)
        c = update(id) { it.copy(oauth = o.copy(pendingState = state, pendingVerifier = verifier), lastError = "") }!!
        OAuth.authorizeUrl(c.oauth, state, verifier)
    }

    /** Handles hermesagent://oauth/callback; runs in the background and reports through the connection's status. */
    fun completeSignIn(uri: Uri) {
        val state = uri.getQueryParameter("state") ?: return
        val c = _connections.value.firstOrNull { it.oauth.pendingState == state } ?: return
        scope.launch {
            val error = uri.getQueryParameter("error")
            if (error != null) {
                update(c.id) { it.copy(oauth = it.oauth.copy(pendingState = "", pendingVerifier = ""), lastError = "Sign-in cancelled: ${uri.getQueryParameter("error_description") ?: error}") }
                return@launch
            }
            try {
                val o = OAuth.exchangeCode(http, c.oauth, uri.getQueryParameter("code") ?: throw IOException("No code in the sign-in reply."))
                update(c.id) { it.copy(oauth = o, lastError = "") }
                clients.remove(c.id)
                refresh(c.id)
            } catch (e: Exception) {
                update(c.id) { it.copy(lastError = e.message ?: "Sign-in failed.") }
            }
        }
    }

    fun signOut(id: String) {
        clients.remove(id)
        update(id) { it.copy(oauth = it.oauth.copy(accessToken = "", refreshToken = "", expiresAt = 0)) }
    }

    // ---- local servers ----

    private fun listening(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; true }.getOrDefault(false)

    /** Starts a stdio MCP server in Termux behind supergateway (stdio → Streamable HTTP on 127.0.0.1) and waits for it. */
    private suspend fun ensureLocalRunning(c: Connection) {
        if (listening(c.port)) return
        termux.launch(localServerScript(c.command, c.port), "MCP: ${c.name}")
        // First start downloads packages through npx, which can take a while.
        repeat(120) {
            delay(1_000)
            if (listening(c.port)) { delay(500); return }
        }
        throw IOException("The server didn't start within 2 minutes. Open Termux to see its output.")
    }

    // ---- calls ----

    suspend fun call(c: Connection, tool: RemoteTool, args: JsonObject, conversationId: String, attach: (Attachment) -> Unit): String =
        withContext(Dispatchers.IO) {
            when (c.type) {
                ConnectionType.MCP_HTTP, ConnectionType.MCP_LOCAL -> {
                    if (c.type == ConnectionType.MCP_LOCAL) ensureLocalRunning(c)
                    val r = try {
                        mcp(c).callTool(tool.name, args)
                    } catch (e: McpAuthRequired) {
                        throw IOException("${c.name} needs you to sign in again under Settings → Connections.")
                    } catch (e: java.net.ConnectException) {
                        clients.remove(c.id); throw e
                    }
                    r.images.forEachIndexed { i, (mime, bytes) ->
                        attach(attachments.saveBytes(conversationId, "${tool.name}-${i + 1}.${mime.substringAfter('/').substringBefore(';')}", bytes, mime, "From ${c.name}"))
                    }
                    if (r.isError) "Error from ${c.name}: ${r.text}" else r.text
                }
                ConnectionType.REST -> {
                    val base = c.url.trim()
                    val request = OpenApi.buildRequest(base, tool, args).newBuilder().apply { headers(c).forEach { (k, v) -> header(k, v) } }.build()
                    http.newCall(request).execute().use { r ->
                        val body = r.body?.string().orEmpty()
                        "HTTP ${r.code} ${request.method} ${request.url.encodedPath}\n${body.take(20_000)}"
                    }
                }
            }
        }

    companion object {
        const val SUPERGATEWAY = "supergateway@4.2.2"

        fun localServerScript(command: String, port: Int) = listOf(
            ToolboxScripts.PATH_EXPORT,
            "command -v npx >/dev/null 2>&1 || pkg install -y nodejs",
            "exec npx -y $SUPERGATEWAY --stdio ${HermesScripts.shellQuote(command.trim())} --outputTransport streamableHttp " +
                "--port $port --host 127.0.0.1 --stateful"
        ).joinToString("\n")
    }
}
