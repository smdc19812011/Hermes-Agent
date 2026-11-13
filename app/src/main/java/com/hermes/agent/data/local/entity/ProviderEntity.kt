package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val apiKey: String,
    val baseUrl: String,
    val defaultModel: String,
    val isEnabled: Boolean,
    val priority: Int,
    val avgResponseMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)
