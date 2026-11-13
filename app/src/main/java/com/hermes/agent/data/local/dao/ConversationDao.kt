package com.hermes.agent.data.local.dao

import androidx.room.*
import com.hermes.agent.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET messageCount = messageCount + 1, lastMessage = :lastMessage, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementMessageCount(id: String, lastMessage: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' OR lastMessage LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<ConversationEntity>>
}
