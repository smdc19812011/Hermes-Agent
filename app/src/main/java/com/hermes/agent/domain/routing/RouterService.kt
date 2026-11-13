package com.hermes.agent.domain.routing

import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.RoutingStrategy
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouterService @Inject constructor(
    private val providerRepository: ProviderRepository
) {
    private val roundRobinCounter = AtomicInteger(0)

    /** Ordered candidates: the first is tried first, the rest are fallbacks. */
    suspend fun resolveProviders(strategy: RoutingStrategy, pinnedProviderId: String? = null): List<Provider> {
        val active = providerRepository.getEnabled().sortedBy { it.priority }
        if (active.isEmpty()) return emptyList()

        pinnedProviderId?.let { id ->
            active.find { it.id == id }?.let { pinned -> return listOf(pinned) + (active - pinned) }
        }

        return when (strategy) {
            RoutingStrategy.PRIORITY -> active
            RoutingStrategy.ROUND_ROBIN -> {
                val start = Math.floorMod(roundRobinCounter.getAndIncrement(), active.size)
                active.drop(start) + active.take(start)
            }
            RoutingStrategy.RANDOM -> active.shuffled()
            // Providers with no measurement yet (0) go first so they get one.
            RoutingStrategy.FASTEST -> active.sortedBy { it.avgResponseMs }
        }
    }
}
