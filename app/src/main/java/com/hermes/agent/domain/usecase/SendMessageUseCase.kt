package com.hermes.agent.domain.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.data.repository.ConversationRepository
import com.hermes.agent.data.repository.LLMRepository
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.data.repository.StreamEvent
import com.hermes.agent.data.tools.ToolApprovals
import com.hermes.agent.data.tools.ToolRegistry
import com.hermes.agent.domain.model.*
import com.hermes.agent.domain.tools.AgentTool
import com.hermes.agent.domain.tools.ChatTurn
import com.hermes.agent.data.media.AttachmentEncoder
import com.hermes.agent.data.media.ImageShrinker
import com.hermes.agent.domain.tools.ToolCall
import com.hermes.agent.domain.tools.ToolContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

private const val MAX_TOOL_ROUNDS = 12
private const val MAX_RESULT_CHARS = 12_000
private const val PANEL_OUTPUT_CHARS = 20_000

class SendMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val llmRepository: LLMRepository,
    private val routerService: com.hermes.agent.domain.routing.RouterService,
    private val settingsRepository: SettingsRepository,
    private val providerRepository: ProviderRepository,
    private val toolRegistry: ToolRegistry,
    private val approvals: ToolApprovals,
    private val toolLibrary: com.hermes.agent.data.toolbox.ToolLibraryRepository,
    private val attachmentStore: com.hermes.agent.data.media.AttachmentStore,
    private val mediaReader: com.hermes.agent.data.media.MediaReader,
    private val modelCatalog: com.hermes.agent.data.repository.ModelCatalogRepository,
    private val agentLibrary: com.hermes.agent.data.agents.AgentLibraryRepository,
    private val connectionRepository: com.hermes.agent.data.connections.ConnectionRepository
) {
    operator fun invoke(
        conversation: Conversation,
        userInput: String,
        history: List<Message>,
        attachments: List<Attachment> = emptyList()
    ): Flow<StreamChunk> = flow {
        val settings = settingsRepository.settings.first()
        val agent = agentLibrary.agent(conversation.agentId)
        // An agent can narrow device access but never widen what Settings allows.
        val access = agent?.deviceAccess?.let { it intersect settings.deviceAccess } ?: settings.deviceAccess

        val userMessage = Message(conversationId = conversation.id, role = Role.USER, content = userInput, attachments = attachments)
        conversationRepository.insertMessage(userMessage)

        if (history.isEmpty() && conversation.title == Conversation.DEFAULT_TITLE) {
            val title = userInput.lineSequence().firstOrNull()?.take(50)?.trim().orEmpty().ifBlank { attachments.firstOrNull()?.name ?: "Attachment" }
            conversationRepository.updateTitle(conversation.id, title)
        }

        val baseSystemPrompt: String? = when (conversation.unrestrictedMode) {
            UnrestrictedMode.NO_SYSTEM -> null
            UnrestrictedMode.CUSTOM -> settings.customUnrestrictedPrompt.ifBlank { DEFAULT_UNRESTRICTED_PROMPT }
            UnrestrictedMode.OFF -> conversation.systemPrompt.ifBlank { settings.globalSystemPrompt }.ifBlank { null }
        }.let { base ->
            if (agent == null || conversation.unrestrictedMode == UnrestrictedMode.NO_SYSTEM) base
            else listOfNotNull(agentPrompt(agent), base).joinToString("\n\n")
        }

        // Routing orders providers; within each provider its models run primary, secondary, ... nth.
        // An agent with its own models uses them, in its order; otherwise the chat's routing applies.
        val agentCandidates = agent?.models?.takeIf { it.isNotEmpty() }
            ?.let { com.hermes.agent.data.agents.AgentRouting.resolve(it, providerRepository.getEnabled()) }.orEmpty()
        if (agent != null && agent.models.isNotEmpty() && agentCandidates.isEmpty()) {
            emit(StreamChunk.Notice("None of ${agent.name}'s models match an enabled provider, so the chat's usual models answered."))
        }
        val candidates = agentCandidates.ifEmpty {
            routerService
                .resolveProviders(conversation.routingStrategy, conversation.pinnedProviderId)
                .flatMap { provider -> provider.models.filter { it.isNotBlank() }.map { model -> provider to model } }
        }.take(settings.maxProviderAttempts.coerceAtLeast(1))

        if (candidates.isEmpty()) {
            emit(StreamChunk.Failed("No enabled providers. Add one from the Providers screen."))
            emit(StreamChunk.Done)
            return@flow
        }

        val shellOn = com.hermes.agent.domain.tools.Capability.SHELL in access
        // Only the new message carries file contents; earlier attachments are named so the model knows they exist.
        val pastTurns: List<ChatTurn> = history.mapNotNull {
            when (it.role) {
                Role.USER -> ChatTurn.User(
                    listOf(it.content, it.attachments.takeIf { a -> a.isNotEmpty() }?.joinToString(prefix = "[Earlier attachments: ", postfix = "]") { a -> a.name })
                        .filter { s -> !s.isNullOrBlank() }.joinToString("\n")
                )
                Role.ASSISTANT -> ChatTurn.Assistant(it.content)
                else -> null
            }
        }
        val local = attachments.filter { !it.isRemote }.map { it to attachmentStore.file(it) }
        val readings = mutableMapOf<String, Result<com.hermes.agent.data.media.Reading>>()

        /** Has the reader look at a file once per message; the activity line shows the user what's happening. */
        suspend fun readOnce(a: Attachment, f: java.io.File): Result<com.hermes.agent.data.media.Reading> = readings.getOrPut(a.uri) {
            val activity = ToolActivity("read-${a.uri}", "read", "Reading ${a.name}", "👁", completed = false)
            emit(StreamChunk.Tool(activity))
            val r = runCatching { mediaReader.read(a, f) }
            emit(StreamChunk.Tool(activity.copy(completed = true)))
            r.onFailure { emit(StreamChunk.Notice("Couldn't read ${a.name}: ${it.message}")) }
            r
        }

        /**
         * The new message for one model: files it can take go as binary parts, files it can't (images for text-only
         * models, video, Office, archives) are read for it, and text files are inlined. [forceText] after a rejection.
         */
        suspend fun turnFor(provider: com.hermes.agent.domain.model.Provider, model: String, forceText: Boolean): ChatTurn.User {
            if (local.isEmpty()) return ChatTurn.User(userInput)
            val info = if (provider.type == com.hermes.agent.domain.model.ProviderType.OPENROUTER) {
                runCatching { modelCatalog.cached(provider.type, provider.effectiveBaseUrl, provider.apiKey) }.getOrNull()?.firstOrNull { it.id == model }
            } else null
            val binary = mutableListOf<Pair<Attachment, java.io.File>>()
            val toRead = mutableListOf<Pair<Attachment, java.io.File>>()
            val rest = mutableListOf<Pair<Attachment, java.io.File>>()
            val ext = { a: Attachment -> a.name.substringAfterLast('.', "").lowercase() }
            for (item in local) {
                val a = item.first
                val directOk = !forceText
                when {
                    provider.isHermes && !forceText -> binary += item // Hermes routes media to its own vision tools
                    a.kind == Attachment.Kind.IMAGE && a.mime != "image/svg+xml" ->
                        if (directOk && info?.inputsImage != false) binary += item else toRead += item
                    a.kind == Attachment.Kind.AUDIO ->
                        if (directOk && info?.inputsAudio == true) binary += item else toRead += item
                    a.mime == "application/pdf" -> if (directOk) binary += item else toRead += item
                    a.kind == Attachment.Kind.VIDEO || ext(a) in setOf("docx", "pptx", "xlsx", "odt", "odp", "ods", "zip", "jar", "apk") -> toRead += item
                    else -> rest += item
                }
            }
            val sections = mutableListOf<String>()
            for ((a, f) in toRead) {
                readOnce(a, f).fold(
                    { sections += "Contents of the attached file \"${a.name}\" (read for you by ${it.by}; treat it as what the file shows):\n${it.text}" },
                    { rest += a to f }
                )
            }
            // Shrinking and base64-encoding images is too heavy for the main thread the chat collects on.
            val enc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                AttachmentEncoder.encode(binary, binary = true, toolsCanOpen = shellOn, shrinkImage = ImageShrinker::shrink) to
                    AttachmentEncoder.encode(rest, binary = false, toolsCanOpen = shellOn, shrinkImage = ImageShrinker::shrink)
            }
            val text = (sections + enc.first.note + enc.second.note + userInput).filter { it.isNotBlank() }.joinToString("\n\n")
            return ChatTurn.User(text, enc.first.parts)
        }
        val installed = if (shellOn) toolLibrary.installedNames() else emptyList()
        var lastError = "unknown error"

        for ((provider, model) in candidates) {
            emit(StreamChunk.ProviderSelected(provider.id, provider.name, model))
            // Hermes brings its own tools; the app's tools are for direct model providers.
            // Connections a loaded skill switched on for the rest of this reply.
            val activated = mutableSetOf<String>()
            var toolsOff = provider.isHermes
            var tools = if (provider.isHermes) emptyList() else toolsFor(access, agent, activated)
            // Skip tools up front for models the catalog says can't use them, and only mention it if the user turned on device access.
            val onlyAgentTools = tools.all { it.capability == com.hermes.agent.domain.tools.Capability.AGENTS }
            if (tools.isNotEmpty() && provider.type == com.hermes.agent.domain.model.ProviderType.OPENROUTER &&
                runCatching { modelCatalog.cached(provider.type, provider.effectiveBaseUrl, provider.apiKey) }.getOrNull()
                    ?.firstOrNull { it.id == model }?.supportsTools == false
            ) {
                if (!onlyAgentTools) emit(StreamChunk.Notice("$model doesn't support tools, so it answered without device access. Pick a model marked \"tools\"."))
                tools = emptyList()
                toolsOff = true
            }
            val attempt = Attempt((pastTurns + turnFor(provider, model, forceText = false)).toMutableList())
            val toolContext = ToolContext(conversation.id, access, delegate = { args ->
                runSubAgent(args, provider to model, access, settings, conversation.id, attempt)
            })
            readings.forEach { (uri, r) ->
                val name = local.firstOrNull { it.first.uri == uri }?.first?.name ?: return@forEach
                attempt.log["read-$uri"] = ToolActivity("read-$uri", "read", r.fold({ "Read $name with ${it.by}" }, { "Couldn't read $name" }), "👁", true)
            }
            val startMs = System.currentTimeMillis()
            try {
                var round = 0
                var budgetHit = false
                while (true) {
                    if (round >= MAX_TOOL_ROUNDS) { budgetHit = true; break }
                    val calls = try {
                        streamRound(provider, model, attempt, systemPrompt(baseSystemPrompt, conversation, tools, installed), tools, settings, conversation.id)
                    } catch (e: Exception) {
                        // A model without vision/file input: send the attachments as descriptions instead.
                        val newest = attempt.turns.lastOrNull { it is ChatTurn.User } as? ChatTurn.User
                        if (e !is CancellationException && !attempt.toolsRan && newest?.parts?.isNotEmpty() == true && looksLikeNoMediaSupport(e)) {
                            attempt.turns[attempt.turns.lastIndexOf(newest)] = turnFor(provider, model, forceText = true)
                            emit(StreamChunk.Notice("$model can't take images or files directly, so they were read for it."))
                            continue
                        }
                        // Many models reject the tools field outright; answer without device access rather than fail.
                        if (e is CancellationException || tools.isEmpty() || attempt.toolsRan || !looksLikeNoToolSupport(e)) throw e
                        tools = emptyList()
                        toolsOff = true
                        if (!onlyAgentTools) emit(StreamChunk.Notice("$model doesn't support tools, so it answered without device access. Pick a model marked \"tools\"."))
                        continue
                    }
                    if (calls.isEmpty()) break
                    attempt.turns += ChatTurn.Assistant(attempt.roundText.toString(), calls)
                    for (call in calls) attempt.turns += runTool(call, tools, toolContext, attempt)
                    if (!toolsOff && activated.addAll(skillConnections(calls))) tools = toolsFor(access, agent, activated)
                    round++
                }
                // Tools ran but no answer followed (the rounds ran out, or the model stopped after a tool): ask for one, without tools.
                if (attempt.turns.any { it is ChatTurn.ToolResult } && (budgetHit || attempt.roundText.isBlank())) {
                    val ask = (if (budgetHit) "You've used all $MAX_TOOL_ROUNDS tool rounds for this message. " else "") +
                        "Now reply to the user in plain text from the tool results above: what you found, what it means, and anything you couldn't check. Don't call tools."
                    try {
                        streamRound(provider, model, attempt, listOfNotNull(systemPrompt(baseSystemPrompt, conversation, tools, installed), ask).joinToString("\n\n"),
                            tools, settings, conversation.id, finalAnswer = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emit(StreamChunk.Notice("$model didn't write a summary (${e.message ?: "error"}), so the tool results are shown instead."))
                    }
                }
                if (attempt.text.isBlank() && attempt.log.isEmpty() && attempt.files.isEmpty()) throw IllegalStateException("empty response")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                emit(StreamChunk.ProviderFailed("${provider.name} · $model", lastError))
                // Tools that already ran may have had effects; retrying elsewhere would repeat them.
                if (attempt.toolsRan) break
                continue
            }

            val durationMs = System.currentTimeMillis() - startMs
            conversationRepository.insertMessage(
                Message(
                    conversationId = conversation.id,
                    role = Role.ASSISTANT,
                    content = attempt.text.toString().trim().ifBlank { toolResultsFallback(attempt.turns) },
                    providerId = provider.id,
                    model = model,
                    durationMs = durationMs,
                    toolLog = attempt.log.values.map { it.display },
                    attachments = attempt.files + linkedImages(attempt.text.toString(), attempt.files)
                )
            )
            providerRepository.updateResponseTime(provider.id, durationMs)
            emit(StreamChunk.Done)
            return@flow
        }

        emit(StreamChunk.Failed("Every model tried failed. Last error: $lastError"))
        emit(StreamChunk.Done)
    }

    private class Attempt(val turns: MutableList<ChatTurn>) {
        val text = StringBuilder()
        val files = mutableListOf<Attachment>()
        var roundText = StringBuilder()
        val log = LinkedHashMap<String, ToolActivity>()
        var toolsRan = false
    }

    /** Streams one model turn into [attempt] and returns the tool calls it asked for. */
    private suspend fun FlowCollector<StreamChunk>.streamRound(
        provider: Provider,
        model: String,
        attempt: Attempt,
        systemPrompt: String?,
        tools: List<AgentTool>,
        settings: AgentSettings,
        conversationId: String,
        finalAnswer: Boolean = false,
        /** False for sub-agents: their text is a tool result, not part of the visible reply. */
        echo: Boolean = true
    ): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        attempt.roundText = StringBuilder()
        if (attempt.text.isNotEmpty()) {
            attempt.text.append("\n\n")
            if (echo) emit(StreamChunk.Token("\n\n"))
        }
        llmRepository
            .streamTurn(provider, model, attempt.turns, systemPrompt, tools, settings.requestTimeoutSeconds, conversationId, finalAnswer)
            .collect { event ->
                when (event) {
                    is StreamEvent.Text -> {
                        attempt.text.append(event.text)
                        attempt.roundText.append(event.text)
                        if (echo) emit(StreamChunk.Token(event.text))
                    }
                    is StreamEvent.Tool -> {
                        val a = event.activity
                        attempt.log[a.id] = attempt.log[a.id]?.copy(completed = a.completed) ?: a
                        attempt.toolsRan = true
                        emit(StreamChunk.Tool(a))
                    }
                    is StreamEvent.Approval -> emit(StreamChunk.ApprovalRequested(provider.id, event.request))
                    is StreamEvent.ToolCalls -> calls += event.calls
                    is StreamEvent.Image -> {
                        val file = runCatching { saveModelImage(event.url, conversationId, model) }.getOrNull()
                        if (file != null) { attempt.files += file; emit(StreamChunk.File(file)) }
                    }
                }
            }
        return calls
    }

    private suspend fun FlowCollector<StreamChunk>.runTool(
        call: ToolCall,
        tools: List<AgentTool>,
        context: ToolContext,
        attempt: Attempt,
        labelPrefix: String = ""
    ): ChatTurn.ToolResult {
        val tool = tools.firstOrNull { it.name == call.name }
            ?: return ChatTurn.ToolResult(call.id, call.name, "Unknown tool ${call.name}.")
        val args = runCatching { JsonParser.parseString(call.arguments).asJsonObject }.getOrDefault(JsonObject())
        val activity = ToolActivity(call.id, tool.name, labelPrefix + tool.label(args), tool.capability.emoji, completed = false)
        attempt.log[call.id] = activity
        emit(StreamChunk.Tool(activity))

        if (tool.requiresApproval(args) && !approvals.isAllowedInChat(context.conversationId, tool.name)) {
            emit(StreamChunk.ApprovalRequested("", ApprovalRequest(
                runId = call.id,
                command = activity.label,
                description = if (tool.capability == com.hermes.agent.domain.tools.Capability.AGENTS) "Skills & agents: the AI wants to save this for every chat."
                    else "${tool.capability.displayName}: the AI wants to run this on your phone.",
                choices = listOf("once", "session", "deny"),
                local = true
            )))
            if (!approvals.await(call.id, context.conversationId, tool.name)) {
                emit(StreamChunk.Tool(activity.copy(completed = true)))
                return ChatTurn.ToolResult(call.id, call.name, "The user denied this command. Don't retry it; ask what they'd like instead.")
            }
        }

        attempt.toolsRan = true
        val posted = java.util.Collections.synchronizedList(mutableListOf<Attachment>())
        val output = try {
            tool.run(args, context.copy(attach = { posted += it }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        posted.toList().forEach { attempt.files += it; emit(StreamChunk.File(it)) }
        val failed = output.startsWith("Error:") || output.startsWith("Error from ")
        val done = activity.copy(completed = true, output = output.take(PANEL_OUTPUT_CHARS), failed = failed)
        attempt.log[call.id] = done
        emit(StreamChunk.Tool(done))
        val clipped = if (output.length > MAX_RESULT_CHARS) output.take(MAX_RESULT_CHARS) + "\n[truncated]" else output
        return ChatTurn.ToolResult(call.id, call.name, clipped)
    }

    private suspend fun saveModelImage(url: String, conversationId: String, model: String): Attachment {
        val decoded = com.hermes.agent.data.media.MediaGenerator.decodeDataUrl(url)
        return if (decoded != null) {
            val ext = decoded.second.substringAfter('/').substringBefore('+').ifBlank { "png" }
            attachmentStore.saveBytes(conversationId, "image-${System.currentTimeMillis() / 1000}.$ext", decoded.first, decoded.second, "Generated by $model")
        } else attachmentStore.download(conversationId, url, caption = "Generated by $model")
    }

    /** Image links in a reply (markdown or bare) become previews, so searched or Hermes-made images show inline. */
    private fun linkedImages(text: String, already: List<Attachment>): List<Attachment> =
        com.hermes.agent.data.media.imageLinks(text)
            .filter { url -> already.none { it.uri == url } }
            .take(8)
            .map { url -> Attachment(url.substringBefore('?').substringAfterLast('/').take(60).ifBlank { "image" }, "image/*", url) }

    /** Tells the model what it can reach, so it uses tools instead of claiming it has no access. */
    private fun systemPrompt(base: String?, conversation: Conversation, tools: List<AgentTool>, installed: List<String>): String? {
        if (tools.isEmpty() || conversation.unrestrictedMode == UnrestrictedMode.NO_SYSTEM) return base
        val reach = tools.map { it.capability.displayName.lowercase() }.distinct().joinToString()
        val note = "You are running inside the Hermes Agent app on the user's Android phone, with tools that act on this phone ($reach). " +
            "When the user asks about their network, nearby devices, connected hardware or the web, call the tools instead of saying you lack access. " +
            "Call several tools in one turn when checking many hosts or ports, and always finish with a written answer that sums up what the tools found." +
            (if (tools.any { it.name == "install_tool" }) " If a program you need is missing, install it with install_tool." else "") +
            (if (installed.isNotEmpty()) " Already installed for you: ${installed.joinToString()}." else "") +
            (if (tools.any { it.name == "post_file" }) " To show the user an image, video, audio or any file, post it with post_file, generate_image or text_to_speech; " +
                "you can build media in Termux (ffmpeg, ImageMagick, Python) and post the result." else "")
        return listOfNotNull(base, note, libraryNote(tools)).joinToString("\n\n")
    }

    /** Lists skills and agents by name and description; full skill text loads on demand with read_skill. */
    private fun libraryNote(tools: List<AgentTool>): String? {
        val names = tools.map { it.name }.toSet()
        val parts = mutableListOf<String>()
        if ("read_skill" in names) {
            val skills = agentLibrary.skills.value
            parts += if (skills.isEmpty()) "No skills are saved yet." else
                "Saved skills (load one with read_skill before a task it covers, then follow it):\n" + skills.joinToString("\n") { sk ->
                    "- ${sk.name}: ${sk.description}" + sk.connections.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (switches on: ", postfix = ")").orEmpty()
                }
        }
        if ("save_skill" in names) parts += "After working out a multi-step procedure that worked and is likely to come up again, save it with save_skill " +
            "(or improve the skill you used if you found a better way), and tell the user you did."
        if ("delegate_task" in names) {
            val agents = agentLibrary.agents.value
            parts += "You can hand self-contained sub-tasks to sub-agents with delegate_task" +
                (if (agents.isEmpty()) "." else ". Saved agents:\n" + agents.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { it.instructions.take(100) }}" })
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * Built-in tools allowed by [access] (and the agent's tool list), plus connection tools: every-chat connections, the agent's own
     * connections, and those a loaded skill switched on ([activated], lowercase names).
     */
    private fun toolsFor(access: Set<com.hermes.agent.domain.tools.Capability>, agent: AgentProfile?, activated: Set<String>): List<AgentTool> {
        val allowed = { name: String -> agent == null || agent.tools.isEmpty() || name in agent.tools }
        val builtIn = toolRegistry.enabled(access).filter { allowed(it.name) }
        if (com.hermes.agent.domain.tools.Capability.CONNECTIONS !in access) return builtIn
        val named = activated + agent?.connections.orEmpty().map { it.lowercase() }
        val fromConnections = connectionRepository.connections.value.filter { it.enabled }.flatMap { c ->
            val asked = c.name.lowercase() in named
            if (!asked && c.scope != com.hermes.agent.data.connections.ToolScope.ALL_CHATS) return@flatMap emptyList()
            c.activeTools.map { com.hermes.agent.data.connections.ConnectionTool(c, it, connectionRepository) }.filter { asked || allowed(it.name) }
        }
        return builtIn + fromConnections.distinctBy { it.name }
    }

    /** Lowercase names of connections that skills loaded by these calls switch on. */
    private fun skillConnections(calls: List<ToolCall>): Set<String> = calls.filter { it.name == "read_skill" }.flatMap { call ->
        val name = runCatching { JsonParser.parseString(call.arguments).asJsonObject.get("name").asString }.getOrNull() ?: return@flatMap emptyList()
        agentLibrary.skill(name)?.connections.orEmpty().map { it.lowercase() }
    }.toSet()

    private fun agentPrompt(agent: AgentProfile) =
        "You are ${agent.name}${agent.description.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}.\n\n${agent.instructions}".trim()

    /**
     * Runs one sub-agent to completion inside the chat's flow, so its tool calls still show in the chat and still ask the user
     * for approval. It starts from only the task and context it's given and can't start sub-agents of its own.
     */
    private suspend fun FlowCollector<StreamChunk>.runSubAgent(
        args: JsonObject,
        parent: Pair<Provider, String>,
        parentAccess: Set<com.hermes.agent.domain.tools.Capability>,
        settings: AgentSettings,
        conversationId: String,
        chat: Attempt
    ): String {
        val task = args.get("task")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty().ifEmpty { error("task is required") }
        val profile = args.get("agent")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }?.let { agentLibrary.agentNamed(it) }
        val providers = providerRepository.getEnabled().filter { !it.isHermes }
        val candidates = profile?.models?.takeIf { it.isNotEmpty() }?.let { com.hermes.agent.data.agents.AgentRouting.resolve(it, providers) }
            ?.takeIf { it.isNotEmpty() } ?: listOf(parent).filter { !it.first.isHermes }
        if (candidates.isEmpty()) error("No model is available for a sub-agent; add a direct provider such as OpenRouter.")
        val access = profile?.deviceAccess?.let { it intersect parentAccess } ?: parentAccess
        val activated = mutableSetOf<String>()
        var tools = toolsFor(access, profile, activated).filter { it.name != "delegate_task" }
        val context = ToolContext(conversationId, access) // no delegate: sub-agents don't nest
        val who = profile?.name ?: "Sub-agent"
        val system = listOfNotNull(
            profile?.let(::agentPrompt),
            "You are a sub-agent working for another AI in the Hermes Agent app on the user's Android phone. Do the task with your tools, " +
                "then reply with a complete, factual report: what you did, what you found (exact values, IPs, paths), and anything you couldn't do. " +
                "Your reply goes to the other AI, not the user.",
            libraryNote(tools)
        ).joinToString("\n\n")
        val prompt = listOfNotNull(args.get("context")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { "Context:\n$it" }, "Task:\n$task")
            .joinToString("\n\n")

        var lastError: Exception? = null
        for ((provider, model) in candidates.take(3)) {
            val sub = Attempt(mutableListOf(ChatTurn.User(prompt)))
            var ranTools = false
            try {
                var rounds = 0
                while (true) {
                    val calls = streamRound(provider, model, sub, system, tools, settings, conversationId, finalAnswer = rounds >= MAX_TOOL_ROUNDS, echo = false)
                    if (calls.isEmpty() || rounds >= MAX_TOOL_ROUNDS) break
                    sub.turns += ChatTurn.Assistant(sub.roundText.toString(), calls)
                    ranTools = true
                    for (call in calls) sub.turns += runTool(call, tools, context, chat, labelPrefix = "↳ $who: ")
                    if (activated.addAll(skillConnections(calls))) tools = toolsFor(access, profile, activated).filter { it.name != "delegate_task" }
                    rounds++
                }
                if (sub.roundText.isBlank() && ranTools) {
                    streamRound(provider, model, sub, "$system\n\nNow write your report from the tool results above. Don't call tools.", tools, settings,
                        conversationId, finalAnswer = true, echo = false)
                }
                chat.files += sub.files
                val report = sub.text.toString().trim().ifEmpty { toolResultsFallback(sub.turns) }
                return "Report from $who ($model):\n$report"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (ranTools) break // its tools may have had effects; don't repeat them on another model
            }
        }
        error("$who failed: ${lastError?.message ?: "no reply"}")
    }

    /** When the model wrote nothing at all, the user still gets what its tools returned. */
    private fun toolResultsFallback(turns: List<ChatTurn>): String {
        val results = turns.filterIsInstance<ChatTurn.ToolResult>().takeLast(6)
        if (results.isEmpty()) return "(no text reply)"
        return "The model didn't write a reply. Here's what its tools returned:\n\n" +
            results.joinToString("\n\n") { r -> "**${r.name}**\n```\n${r.content.trim().take(1500)}\n```" }
    }

    private fun looksLikeNoMediaSupport(e: Exception): Boolean {
        val m = e.message?.lowercase() ?: return false
        val media = listOf("image", "vision", "multimodal", "file", "pdf", "audio", "input").any { it in m }
        return media && listOf("support", "not allowed", "invalid", "no endpoints", "unsupported").any { it in m }
    }

    private fun looksLikeNoToolSupport(e: Exception): Boolean {
        val m = e.message?.lowercase() ?: return false
        return "tool" in m && ("support" in m || "not allowed" in m || "unsupported" in m || "no endpoints" in m || "invalid" in m)
    }
}
