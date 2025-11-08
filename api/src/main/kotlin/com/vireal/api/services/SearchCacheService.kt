package com.vireal.api.services

import com.vireal.shared.models.Note
import com.vireal.shared.models.SearchResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Улучшенный сервис кэширования для масштабируемого поиска
 */
class SearchCacheService {

  /**
   * Базовый интерфейс для кэшируемых данных
   */
  private interface CacheEntry {
    val timestamp: Long
    fun isExpired(ttlMillis: Long): Boolean {
      return System.currentTimeMillis() - timestamp > ttlMillis
    }
  }

  /**
   * Кэш для списка заметок (обратная совместимость)
   */
  private data class NotesCacheEntry(
    val results: List<Note>,
    override val timestamp: Long
  ) : CacheEntry

  /**
   * Кэш для результатов поиска
   */
  data class SearchResultCacheEntry(
    val result: SearchResult,
    override val timestamp: Long,
    val hitCount: Int = 0
  ) : CacheEntry

  // Разные кэши для разных типов данных
  private val notesCache = ConcurrentHashMap<String, NotesCacheEntry>()
  private val searchCache = ConcurrentHashMap<String, SearchResultCacheEntry>()

  // Mutex для синхронизации критических секций
  private val mutex = Mutex()

  // Настройки кэша
  companion object {
    private const val DEFAULT_CACHE_TTL = 5 * 60 * 1000L  // 5 минут
    private const val MAX_CACHE_SIZE = 100
    private const val CLEANUP_THRESHOLD = 150  // Запускаем очистку при превышении
  }

  /**
   * Оригинальный метод для обратной совместимости
   * Получить или вычислить список заметок
   */
  suspend fun getOrCompute(
    userId: Long,
    query: String,
    compute: suspend () -> List<Note>
  ): List<Note> = mutex.withLock {
    val key = generateKey(userId, query)
    val cached = notesCache[key]

    if (cached != null && !cached.isExpired(DEFAULT_CACHE_TTL)) {
      return cached.results
    }

    val results = compute()
    notesCache[key] = NotesCacheEntry(results, System.currentTimeMillis())

    // Автоматическая очистка при превышении размера
    if (notesCache.size > MAX_CACHE_SIZE) {
      cleanupNotesCache()
    }

    results
  }

  /**
   * Получить SearchResult из кэша
   */
  suspend fun get(key: String): SearchResult? = mutex.withLock {
    val cached = searchCache[key]

    if (cached != null && !cached.isExpired(DEFAULT_CACHE_TTL)) {
      // Увеличиваем счетчик попаданий
      searchCache[key] = cached.copy(hitCount = cached.hitCount + 1)
      return cached.result
    }

    return null
  }

  /**
   * Сохранить SearchResult в кэш
   */
  suspend fun put(
    key: String,
    value: SearchResult,
    ttlMinutes: Long = 5
  ) = mutex.withLock {
    val ttlMillis = ttlMinutes * 60 * 1000
    searchCache[key] = SearchResultCacheEntry(
      result = value,
      timestamp = System.currentTimeMillis(),
      hitCount = 0
    )

    // Автоматическая очистка при превышении размера
    if (searchCache.size > MAX_CACHE_SIZE) {
      cleanupSearchCache()
    }
  }

  /**
   * Полная очистка всех кэшей
   */
  suspend fun cleanup() = mutex.withLock {
    cleanupNotesCache()
    cleanupSearchCache()
  }

  /**
   * Генерация ключа для кэша
   */
  private fun generateKey(userId: Long, query: String): String {
    return "$userId:${query.lowercase().trim()}"
  }

  /**
   * Очистка устаревших записей в кэше заметок
   */
  private fun cleanupNotesCache() {
    val cutoff = System.currentTimeMillis() - DEFAULT_CACHE_TTL
    notesCache.entries.removeIf { it.value.timestamp < cutoff }

    // Если все еще слишком много, удаляем самые старые
    if (notesCache.size > MAX_CACHE_SIZE) {
      val entriesToRemove = notesCache.entries
        .sortedBy { it.value.timestamp }
        .take(notesCache.size - MAX_CACHE_SIZE)

      entriesToRemove.forEach { notesCache.remove(it.key) }
    }
  }

  /**
   * Очистка устаревших записей в кэше поиска
   */
  private fun cleanupSearchCache() {
    val cutoff = System.currentTimeMillis() - DEFAULT_CACHE_TTL
    searchCache.entries.removeIf { it.value.timestamp < cutoff }

    // Удаляем наименее используемые записи при превышении лимита
    if (searchCache.size > MAX_CACHE_SIZE) {
      val entriesToRemove = searchCache.entries
        .sortedBy { it.value.hitCount }
        .take(searchCache.size - MAX_CACHE_SIZE)

      entriesToRemove.forEach { searchCache.remove(it.key) }
    }
  }
}
