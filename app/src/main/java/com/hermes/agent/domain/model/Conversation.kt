package com.hermes.agent.domain.model

import com.hermes.agent.data.local.entity.ConversationEntity
import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_TITLE,
    val systemPrompt: String = "",
    val unrestrictedMode: UnrestrictedMode = UnrestrictedMode.OFF,
    val routingStrategy: RoutingStrategy = RoutingStrategy.PRIORITY,
    val pinnedProviderId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastMessage: String = ""
) {
    companion object {
        const val DEFAULT_TITLE = "New conversation"
    }
}

fun ConversationEntity.toDomain() = Conversation(
    id = id,
    title = title,
    systemPrompt = systemPrompt,
    unrestrictedMode = UnrestrictedMode.valueOf(unrestrictedMode),
    routingStrategy = RoutingStrategy.valueOf(routingStrategy),
    pinnedProviderId = pinnedProviderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messageCount = messageCount,
    lastMessage = lastMessage
)

fun Conversation.toEntity() = ConversationEntity(
    id = id,
    title = title,
    systemPrompt = systemPrompt,
    unrestrictedMode = unrestrictedMode.name,
    routingStrategy = routingStrategy.name,
    pinnedProviderId = pinnedProviderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messageCount = messageCount,
    lastMessage = lastMessage
)
