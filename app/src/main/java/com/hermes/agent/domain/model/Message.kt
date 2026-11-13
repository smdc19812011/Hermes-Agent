package com.hermes.agent.domain.model

import com.hermes.agent.data.local.entity.MessageEntity
import java.util.UUID

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: Role,
    val content: String,
    val providerId: String? = null,
    val model: String? = null,
    val tokenCount: Int = 0,
    val durationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

fun MessageEntity.toDomain() = Message(
    id = id,
    conversationId = conversationId,
    role = Role.valueOf(role),
    content = content,
    providerId = providerId,
    model = model,
    tokenCount = tokenCount,
    durationMs = durationMs,
    createdAt = createdAt
)

fun Message.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    providerId = providerId,
    model = model,
    tokenCount = tokenCount,
    durationMs = durationMs,
    createdAt = createdAt
)
