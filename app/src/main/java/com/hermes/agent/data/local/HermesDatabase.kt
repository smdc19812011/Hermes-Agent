package com.hermes.agent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.dao.ProviderDao
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.local.entity.ProviderEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ProviderEntity::class],
    version = 3,
    exportSchema = false
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolLog TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE providers ADD COLUMN models TEXT")
            }
        }
    }
}
