package com.vireal.api.services

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.vireal.shared.models.Note

class SearchCacheService {
    private data class CacheEntry(
        val results: List<Note>,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val mutex = Mutex()
    private val CACHE_TTL = 5 * 60 * 1000 // 5 минут

    suspend fun getOrCompute(
        userId: Long,
        query: String,
        compute: suspend () -> List<Note>
    ): List<Note> = mutex.withLock {
        val key = "$userId:${query.lowercase()}"
        val cached = cache[key]

        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL) {
            return cached.results
        }

        val results = compute()
        cache[key] = CacheEntry(results, System.currentTimeMillis())

        if (cache.size > 100) {
            val cutoff = System.currentTimeMillis() - CACHE_TTL
            cache.entries.removeIf { it.value.timestamp < cutoff }
        }

        results
    }
}