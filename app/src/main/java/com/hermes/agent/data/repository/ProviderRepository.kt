package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.ProviderDao
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.toDomain
import com.hermes.agent.domain.model.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRepository @Inject constructor(private val dao: ProviderDao) {

    fun observeAll(): Flow<List<Provider>> = dao.observeAll().map { it.map { e -> e.toDomain() } }

    suspend fun getEnabled(): List<Provider> = dao.getEnabled().map { it.toDomain() }

    suspend fun getById(id: String): Provider? = dao.getById(id)?.toDomain()

    suspend fun save(provider: Provider) = dao.upsert(provider.toEntity())

    suspend fun delete(provider: Provider) = dao.delete(provider.toEntity())

    suspend fun nextPriority(): Int = (dao.maxPriority() ?: -1) + 1

    suspend fun updateResponseTime(id: String, durationMs: Long) {
        val existing = dao.getById(id)?.avgResponseMs ?: 0L
        val updated = if (existing <= 0L) durationMs else (existing * 3 + durationMs) / 4
        dao.updateResponseTime(id, updated)
    }
}
