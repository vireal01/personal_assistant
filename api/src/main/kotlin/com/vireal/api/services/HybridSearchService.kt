package com.vireal.api.services

import com.vireal.api.data.repository.NotesRepository
import com.vireal.api.data.repository.VectorSearchRepository
import com.vireal.shared.models.Note
import com.vireal.shared.models.SearchResult
import kotlinx.coroutines.*
import java.security.MessageDigest

/**
 * Масштабируемый гибридный поисковый сервис
 * Оптимизирован для работы с 10k+ заметок
 */
class HybridSearchService(
  private val notesRepository: NotesRepository = NotesRepository(),
  private val vectorRepository: VectorSearchRepository = VectorSearchRepository(),
  private val embeddingService: EmbeddingService = EmbeddingService(),
  private val cacheService: SearchCacheService = SearchCacheService()
) {

  companion object {
    // Параметры поиска
    private const val VECTOR_SEARCH_LIMIT = 100     // Первичный лимит для векторного поиска
    private const val TEXT_SEARCH_LIMIT = 50        // Лимит для текстового поиска
    private const val FINAL_RESULTS_LIMIT = 20      // Финальное количество результатов

    // Веса для гибридного поиска
    private const val DEFAULT_VECTOR_WEIGHT = 0.7
    private const val DEFAULT_TEXT_WEIGHT = 0.3

    // Пороги релевантности
    private const val HIGH_RELEVANCE_THRESHOLD = 0.8
    private const val MEDIUM_RELEVANCE_THRESHOLD = 0.5
    private const val LOW_RELEVANCE_THRESHOLD = 0.2

    // Кэширование
    private const val CACHE_TTL_MINUTES = 10L
  }

  /**
   * Основной метод поиска с автоматическим выбором стратегии
   */
  suspend fun search(
    userId: Long,
    query: String,
    limit: Int = FINAL_RESULTS_LIMIT,
    useCache: Boolean = true
  ): SearchResult = coroutineScope {

    println("Starting hybrid search for user $userId: '$query'")
    val startTime = System.currentTimeMillis()

    // 1. Проверяем кэш
    if (useCache) {
      val cachedResult = getCachedResult(userId, query)
      if (cachedResult != null) {
        println("Cache hit! Returning cached results")
        return@coroutineScope cachedResult
      }
    }

    // 2. Параллельно запускаем векторный и текстовый поиск
    val vectorSearchDeferred = async {
      performVectorSearch(userId, query)
    }

    val textSearchDeferred = async {
      performTextSearch(userId, query)
    }

    // 3. Ждем результаты обоих поисков
    val vectorResults = vectorSearchDeferred.await()
    val textResults = textSearchDeferred.await()

    // 4. Объединяем и ранжируем результаты
    val mergedResults = mergeAndRankResults(
      vectorResults = vectorResults,
      textResults = textResults,
      query = query,
      limit = limit
    )

    // 5. Создаем результат
    val searchResult = SearchResult(
      notes = mergedResults,
      totalFound = mergedResults.size
    )

    // 6. Кэшируем результат
    if (useCache && mergedResults.isNotEmpty()) {
      cacheResult(userId, query, searchResult)
    }

    val searchTime = System.currentTimeMillis() - startTime
    println("Search completed in ${searchTime}ms, found ${mergedResults.size} results")

    // 7. Асинхронно обновляем статистику
    launch {
      updateSearchStatistics(userId, searchTime)
    }

    searchResult
  }

  /**
   * Векторный поиск с использованием pgvector
   */
  private suspend fun performVectorSearch(
    userId: Long,
    query: String
  ): List<Pair<Note, Double>> {

    // Создаем embedding для запроса
    val queryEmbedding = embeddingService.createEmbedding(query)
      ?: return emptyList()  // Если embedding недоступен, возвращаем пустой список

    println("Performing vector search...")

    // Используем оптимизированный векторный поиск
    return vectorRepository.searchByVector(
      userId = userId,
      queryEmbedding = queryEmbedding,
      limit = VECTOR_SEARCH_LIMIT,
      threshold = LOW_RELEVANCE_THRESHOLD
    )
  }

  /**
   * Полнотекстовый поиск с использованием PostgreSQL FTS
   */
  private suspend fun performTextSearch(
    userId: Long,
    query: String
  ): List<Note> {
    println("Performing text search...")

    return notesRepository.searchNotesFullText(
      userId = userId,
      query = query,
      limit = TEXT_SEARCH_LIMIT
    )
  }

  /**
   * Объединение и ранжирование результатов из разных источников
   */
  private fun mergeAndRankResults(
    vectorResults: List<Pair<Note, Double>>,
    textResults: List<Note>,
    query: String,
    limit: Int
  ): List<Note> {

    // Создаем map для объединения результатов
    val scoredNotes = mutableMapOf<String, ScoredNote>()

    // Добавляем векторные результаты
    vectorResults.forEach { (note, similarity) ->
      scoredNotes[note.id] = ScoredNote(
        note = note,
        vectorScore = similarity,
        textScore = 0.0,
        finalScore = similarity * DEFAULT_VECTOR_WEIGHT
      )
    }

    // Добавляем/обновляем текстовые результаты
    textResults.forEachIndexed { index, note ->
      val textScore = 1.0 - (index.toDouble() / textResults.size)  // Простой score на основе позиции

      val existing = scoredNotes[note.id]
      if (existing != null) {
        // Обновляем существующий результат
        scoredNotes[note.id] = existing.copy(
          textScore = textScore,
          finalScore = calculateFinalScore(existing.vectorScore, textScore)
        )
      } else {
        // Добавляем новый результат
        scoredNotes[note.id] = ScoredNote(
          note = note,
          vectorScore = 0.0,
          textScore = textScore,
          finalScore = textScore * DEFAULT_TEXT_WEIGHT
        )
      }
    }

    // Сортируем по финальному score и возвращаем топ результаты
    return scoredNotes.values
      .sortedByDescending { it.finalScore }
      .take(limit)
      .map { it.note }
  }

  /**
   * Расчет финального score с учетом различных факторов
   */
  private fun calculateFinalScore(
    vectorScore: Double,
    textScore: Double
  ): Double {
    // Адаптивные веса в зависимости от качества совпадений
    val (vectorWeight, textWeight) = when {
      vectorScore > HIGH_RELEVANCE_THRESHOLD -> {
        // Отличное векторное совпадение - приоритет векторному поиску
        0.8 to 0.2
      }

      textScore > HIGH_RELEVANCE_THRESHOLD -> {
        // Отличное текстовое совпадение - приоритет тексту
        0.3 to 0.7
      }

      vectorScore > MEDIUM_RELEVANCE_THRESHOLD && textScore > MEDIUM_RELEVANCE_THRESHOLD -> {
        // Хорошие совпадения обоих типов - сбалансированный подход
        0.5 to 0.5
      }

      else -> {
        // Стандартные веса
        DEFAULT_VECTOR_WEIGHT to DEFAULT_TEXT_WEIGHT
      }
    }

    return (vectorScore * vectorWeight) + (textScore * textWeight)
  }

  /**
   * Поиск без embeddings (fallback режим)
   */
  suspend fun searchWithoutEmbeddings(
    userId: Long,
    query: String,
    limit: Int = FINAL_RESULTS_LIMIT
  ): SearchResult {
    println("Performing search without embeddings (fallback mode)")

    // Используем только текстовый поиск
    val notes = notesRepository.searchNotesFullText(
      userId = userId,
      query = query,
      limit = limit * 2  // Берем больше для компенсации отсутствия векторного поиска
    ).take(limit)

    return SearchResult(
      notes = notes,
      totalFound = notes.size
    )
  }

  /**
   * Обработка заметок без embeddings в фоновом режиме
   */
  suspend fun processNotesWithoutEmbeddings(
    userId: Long,
    batchSize: Int = 100
  ): Int = coroutineScope {
    println("Processing notes without embeddings for user $userId")

    var totalProcessed = 0
    var hasMore = true

    while (hasMore) {
      // Получаем батч заметок без embeddings
      val notesWithoutEmbeddings = vectorRepository.getNotesWithoutEmbeddings(
        userId = userId,
        limit = batchSize
      )

      if (notesWithoutEmbeddings.isEmpty()) {
        hasMore = false
        break
      }

      println("Processing batch of ${notesWithoutEmbeddings.size} notes")

      // Создаем embeddings параллельно для ускорения
      val embeddings = notesWithoutEmbeddings.map { (noteId, content) ->
        async {
          val embedding = embeddingService.createEmbedding(content)
          if (embedding != null) {
            noteId to embedding
          } else null
        }
      }.awaitAll().filterNotNull()

      // Batch update в БД
      if (embeddings.isNotEmpty()) {
        val updated = vectorRepository.updateEmbeddingsBatch(embeddings)
        totalProcessed += updated
        println("Updated $updated embeddings")
      }

      // Небольшая задержка между батчами для снижения нагрузки
      if (hasMore) {
        delay(100)
      }
    }

    println("Processed $totalProcessed notes total")
    totalProcessed
  }

  /**
   * Получение результата из кэша
   */
  private suspend fun getCachedResult(
    userId: Long,
    query: String
  ): SearchResult? {
    val cacheKey = generateCacheKey(userId, query)
    return cacheService.get(cacheKey)
  }

  /**
   * Сохранение результата в кэш
   */
  private suspend fun cacheResult(
    userId: Long,
    query: String,
    result: SearchResult
  ) {
    val cacheKey = generateCacheKey(userId, query)
    cacheService.put(
      key = cacheKey,
      value = result,
      ttlMinutes = CACHE_TTL_MINUTES
    )
  }

  /**
   * Генерация ключа для кэша
   */
  private fun generateCacheKey(userId: Long, query: String): String {
    val normalized = query.lowercase().trim()
    val hash = MessageDigest.getInstance("SHA-256")
      .digest("$userId:$normalized".toByteArray())
      .fold("") { str, it -> str + "%02x".format(it) }
    return "search:$userId:$hash"
  }

  /**
   * Обновление статистики поиска (для мониторинга производительности)
   */
  private suspend fun updateSearchStatistics(userId: Long, searchTimeMs: Long) {
    // Здесь можно добавить логику сохранения статистики в БД
    // Например, среднее время поиска, количество запросов и т.д.
    println("Search statistics - User: $userId, Time: ${searchTimeMs}ms")
  }

  /**
   * Очистка устаревшего кэша
   */
  suspend fun cleanupExpiredCache() {
    cacheService.cleanup()
  }

  /**
   * Вспомогательный класс для хранения оценок
   */
  private data class ScoredNote(
    val note: Note,
    val vectorScore: Double,
    val textScore: Double,
    val finalScore: Double
  )
}
