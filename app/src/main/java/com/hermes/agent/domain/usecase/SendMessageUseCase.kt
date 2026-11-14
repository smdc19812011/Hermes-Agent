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
import com.hermes.agent.domain.tools.ToolCall
import com.hermes.agent.domain.tools.ToolContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

private const val MAX_TOOL_ROUNDS = 8
private const val MAX_RESULT_CHARS = 12_000

class SendMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val llmRepository: LLMRepository,
    private val routerService: com.hermes.agent.domain.routing.RouterService,
    private val settingsRepository: SettingsRepository,
    private val providerRepository: ProviderRepository,
    private val toolRegistry: ToolRegistry,
    private val approvals: ToolApprovals,
    private val toolLibrary: com.hermes.agent.data.toolbox.ToolLibraryRepository,
    private val attachmentStore: com.hermes.agent.data.media.AttachmentStore
) {
    operator fun invoke(
        conversation: Conversation,
        userInput: String,
        history: List<Message>
    ): Flow<StreamChunk> = flow {
        val settings = settingsRepository.settings.first()

        val userMessage = Message(conversationId = conversation.id, role = Role.USER, content = userInput)
        conversationRepository.insertMessage(userMessage)

        if (history.isEmpty() && conversation.title == Conversation.DEFAULT_TITLE) {
            conversationRepository.updateTitle(conversation.id, userInput.lineSequence().first().take(50).trim())
        }

        val baseSystemPrompt: String? = when (conversation.unrestrictedMode) {
            UnrestrictedMode.NO_SYSTEM -> null
            UnrestrictedMode.CUSTOM -> settings.customUnrestrictedPrompt.ifBlank { DEFAULT_UNRESTRICTED_PROMPT }
            UnrestrictedMode.OFF -> conversation.systemPrompt.ifBlank { settings.globalSystemPrompt }.ifBlank { null }
        }

        // Routing orders providers; within each provider its models run primary, secondary, ... nth.
        val candidates = routerService
            .resolveProviders(conversation.routingStrategy, conversation.pinnedProviderId)
            .flatMap { provider -> provider.models.filter { it.isNotBlank() }.map { model -> provider to model } }
            .take(settings.maxProviderAttempts.coerceAtLeast(1))

        if (candidates.isEmpty()) {
            emit(StreamChunk.Failed("No enabled providers. Add one from the Providers screen."))
            emit(StreamChunk.Done)
            return@flow
        }

        val baseTurns: List<ChatTurn> = history.mapNotNull {
            when (it.role) {
                Role.USER -> ChatTurn.User(it.content)
                Role.ASSISTANT -> ChatTurn.Assistant(it.content)
                else -> null
            }
        } + ChatTurn.User(userInput)
        val toolContext = ToolContext(conversation.id, settings.deviceAccess)
        val installed = if (com.hermes.agent.domain.tools.Capability.SHELL in settings.deviceAccess) toolLibrary.installedNames() else emptyList()
        var lastError = "unknown error"

        for ((provider, model) in candidates) {
            emit(StreamChunk.ProviderSelected(provider.id, provider.name, model))
            // Hermes brings its own tools; the app's tools are for direct model providers.
            var tools = if (provider.isHermes) emptyList() else toolRegistry.enabled(settings.deviceAccess)
            val attempt = Attempt(baseTurns.toMutableList())
            val startMs = System.currentTimeMillis()
            try {
                var round = 0
                while (round < MAX_TOOL_ROUNDS) {
                    val calls = try {
                        streamRound(provider, model, attempt, systemPrompt(baseSystemPrompt, conversation, tools, installed), tools, settings, conversation.id)
                    } catch (e: Exception) {
                        // Many models reject the tools field outright; answer without device access rather than fail.
                        if (e is CancellationException || tools.isEmpty() || attempt.toolsRan || !looksLikeNoToolSupport(e)) throw e
                        tools = emptyList()
                        emit(StreamChunk.Notice("$model doesn't support tools, so it answered without device access. Pick a model marked \"tools\"."))
                        continue
                    }
                    if (calls.isEmpty()) break
                    attempt.turns += ChatTurn.Assistant(attempt.roundText.toString(), calls)
                    for (call in calls) attempt.turns += runTool(call, tools, toolContext, attempt)
                    round++
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
                    content = attempt.text.toString().trim().ifBlank { "(no text reply)" },
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
        conversationId: String
    ): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        attempt.roundText = StringBuilder()
        if (attempt.text.isNotEmpty()) {
            attempt.text.append("\n\n")
            emit(StreamChunk.Token("\n\n"))
        }
        llmRepository
            .streamTurn(provider, model, attempt.turns, systemPrompt, tools, settings.requestTimeoutSeconds, conversationId)
            .collect { event ->
                when (event) {
                    is StreamEvent.Text -> {
                        attempt.text.append(event.text)
                        attempt.roundText.append(event.text)
                        emit(StreamChunk.Token(event.text))
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
        attempt: Attempt
    ): ChatTurn.ToolResult {
        val tool = tools.firstOrNull { it.name == call.name }
            ?: return ChatTurn.ToolResult(call.id, call.name, "Unknown tool ${call.name}.")
        val args = runCatching { JsonParser.parseString(call.arguments).asJsonObject }.getOrDefault(JsonObject())
        val activity = ToolActivity(call.id, tool.name, tool.label(args), tool.capability.emoji, completed = false)
        attempt.log[call.id] = activity
        emit(StreamChunk.Tool(activity))

        if (tool.requiresApproval(args) && !approvals.isAllowedInChat(context.conversationId, tool.name)) {
            emit(StreamChunk.ApprovalRequested("", ApprovalRequest(
                runId = call.id,
                command = activity.label,
                description = "${tool.capability.displayName}: the AI wants to run this on your phone.",
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
        attempt.log[call.id] = activity.copy(completed = true)
        emit(StreamChunk.Tool(activity.copy(completed = true)))
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
            "When the user asks about their network, nearby devices, connected hardware or the web, call the tools instead of saying you lack access." +
            (if (tools.any { it.name == "install_tool" }) " If a program you need is missing, install it with install_tool." else "") +
            (if (installed.isNotEmpty()) " Already installed for you: ${installed.joinToString()}." else "") +
            (if (tools.any { it.name == "post_file" }) " To show the user an image, video, audio or any file, post it with post_file, generate_image or text_to_speech; " +
                "you can build media in Termux (ffmpeg, ImageMagick, Python) and post the result." else "")
        return listOfNotNull(base, note).joinToString("\n\n")
    }

    private fun looksLikeNoToolSupport(e: Exception): Boolean {
        val m = e.message?.lowercase() ?: return false
        return "tool" in m && ("support" in m || "not allowed" in m || "unsupported" in m || "no endpoints" in m || "invalid" in m)
    }
}
