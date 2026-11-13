package com.hermes.agent.domain.usecase

import com.hermes.agent.data.repository.ConversationRepository
import com.hermes.agent.data.repository.LLMRepository
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.domain.model.*
import com.hermes.agent.domain.routing.RouterService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val llmRepository: LLMRepository,
    private val routerService: RouterService,
    private val settingsRepository: SettingsRepository,
    private val providerRepository: ProviderRepository
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

        val systemPrompt: String? = when (conversation.unrestrictedMode) {
            UnrestrictedMode.NO_SYSTEM -> null
            UnrestrictedMode.CUSTOM -> settings.customUnrestrictedPrompt.ifBlank { DEFAULT_UNRESTRICTED_PROMPT }
            UnrestrictedMode.OFF -> conversation.systemPrompt.ifBlank { settings.globalSystemPrompt }.ifBlank { null }
        }

        val candidates = routerService
            .resolveProviders(conversation.routingStrategy, conversation.pinnedProviderId)
            .take(settings.maxProviderAttempts.coerceAtLeast(1))

        if (candidates.isEmpty()) {
            emit(StreamChunk.Failed("No enabled providers. Add one from the Providers screen."))
            emit(StreamChunk.Done)
            return@flow
        }

        val apiHistory = history + userMessage
        var lastError = "unknown error"

        for (provider in candidates) {
            emit(StreamChunk.ProviderSelected(provider.id, provider.name, provider.defaultModel))
            val buffer = StringBuilder()
            val startMs = System.currentTimeMillis()
            try {
                llmRepository
                    .streamCompletion(provider, apiHistory, systemPrompt, settings.requestTimeoutSeconds)
                    .collect { token ->
                        buffer.append(token)
                        emit(StreamChunk.Token(token))
                    }
                if (buffer.isBlank()) throw IllegalStateException("empty response")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                emit(StreamChunk.ProviderFailed(provider.name, lastError))
                continue
            }

            val durationMs = System.currentTimeMillis() - startMs
            conversationRepository.insertMessage(
                Message(
                    conversationId = conversation.id,
                    role = Role.ASSISTANT,
                    content = buffer.toString(),
                    providerId = provider.id,
                    model = provider.defaultModel,
                    durationMs = durationMs
                )
            )
            providerRepository.updateResponseTime(provider.id, durationMs)
            emit(StreamChunk.Done)
            return@flow
        }

        emit(StreamChunk.Failed("All providers failed. Last error: $lastError"))
        emit(StreamChunk.Done)
    }
}
