package com.hermes.agent.data.local.dao

import androidx.room.*
import com.hermes.agent.data.local.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY priority ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY priority ASC")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE isEnabled = 1 ORDER BY priority ASC")
    suspend fun getEnabled(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: String): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Delete
    suspend fun delete(provider: ProviderEntity)

    @Query("UPDATE providers SET avgResponseMs = :avgMs WHERE id = :id")
    suspend fun updateResponseTime(id: String, avgMs: Long)

    @Query("SELECT MAX(priority) FROM providers")
    suspend fun maxPriority(): Int?
}
