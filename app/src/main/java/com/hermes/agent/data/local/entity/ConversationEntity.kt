package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val systemPrompt: String,
    val unrestrictedMode: String,
    val routingStrategy: String,
    val pinnedProviderId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val lastMessage: String = "",
    val agentId: String? = null
)
