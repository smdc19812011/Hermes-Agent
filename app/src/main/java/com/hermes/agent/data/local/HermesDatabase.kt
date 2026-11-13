package com.hermes.agent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.dao.ProviderDao
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.local.entity.ProviderEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ProviderEntity::class],
    version = 1,
    exportSchema = false
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
}
