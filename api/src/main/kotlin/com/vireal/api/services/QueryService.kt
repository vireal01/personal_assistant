package com.vireal.api.services

import com.vireal.api.data.repository.NotesRepository
import com.vireal.api.data.repository.VectorSearchRepository
import com.vireal.shared.models.Note
import com.vireal.shared.models.QueryResponse
import java.time.Duration
import java.time.Instant
import kotlin.collections.take

class QueryService(
    private val notesRepository: NotesRepository = NotesRepository(),
    private val vectorRepository: VectorSearchRepository = VectorSearchRepository(),
    private val embeddingService: EmbeddingService = EmbeddingService(),
    private val llmService: LLMService = LLMService(),
    private val cacheService: SearchCacheService = SearchCacheService(),
    private val tagService: TagExtractionService = TagExtractionService()
) {
    companion object {
        private const val MAX_CONTEXT_TOKENS = 2000
        private const val MIN_KEYWORD_LENGTH = 2
        private const val RERANKING_CANDIDATES = 50
        private const val FINAL_RESULTS = 5
        private const val FALLBACK_RESULTS = 10
    }

    suspend fun processQuery(userId: Long, question: String): QueryResponse {
        println("=== Processing query: '$question' for user: $userId ===")

        // Извлекаем теги и категорию из вопроса
        val (queryTags, queryCategory) = tagService.extractTagsAndCategory(question)
        println("Extracted tags: $queryTags, category: $queryCategory")

        // Используем кеш для частых запросов
        val notes = cacheService.getOrCompute(userId, question) {
            searchWithFilters(userId, question, queryTags, queryCategory)
        }

        println("Found ${notes.size} relevant notes")

        // Формируем оптимизированный контекст
        val context = buildOptimizedContext(notes, question)

        // Генерируем ответ
        val answer = if (context.isNotEmpty()) {
            llmService.generateAnswer(context, question)
        } else {
            "К сожалению, в базе знаний не найдено информации по вашему вопросу. " +
                    "Попробуйте добавить соответствующие записи."
        }

        return QueryResponse(
            answer = answer,
            sources = notes.take(3).map { note ->
                "${note.content.take(100)}${if (note.content.length > 100) "..." else ""}"
            }
        )
    }

    private suspend fun searchWithFilters(
        userId: Long,
        question: String,
        tags: List<String>,
        category: String?
    ): List<Note> {
        // 1. Создаем embedding для вопроса
        val queryEmbedding = embeddingService.createEmbedding(question)

        // 2. Если embedding недоступен, используем текстовый поиск
        if (queryEmbedding == null) {
            println("Embedding unavailable, falling back to text search")
            return notesRepository.searchNotesFullText(userId, question, limit = FALLBACK_RESULTS)
        }

        // 3. Первичный векторный поиск с фильтрами
        var vectorResults = vectorRepository.searchByVectorWithFilters(
            userId = userId,
            queryEmbedding = queryEmbedding,
            tags = tags.takeIf { it.isNotEmpty() },
            category = category,
            limit = RERANKING_CANDIDATES,
            threshold = 0.2
        )

        // 4. Если мало результатов с фильтрами, расширяем поиск
        if (vectorResults.size < 3) {
            println("Too few results with filters (${vectorResults.size}), expanding search")

            val unfilteredResults = vectorRepository.searchByVectorWithFilters(
                userId = userId,
                queryEmbedding = queryEmbedding,
                limit = RERANKING_CANDIDATES,
                threshold = 0.3
            )

            // Объединяем результаты, сохраняя уникальность
            val combinedResults = (vectorResults + unfilteredResults)
                .distinctBy { it.first.id }
                .sortedByDescending { it.second }
                .take(RERANKING_CANDIDATES)

            vectorResults = combinedResults
        }

        // 5. Если все еще нет результатов, используем fallback
        if (vectorResults.isEmpty()) {
            println("No vector results, using text search fallback")
            return notesRepository.searchNotesFullText(userId, question, FALLBACK_RESULTS)
        }

        // 6. Применяем reranking
        return rerankResults(vectorResults, question)
    }

    private suspend fun rerankResults(
        candidates: List<Pair<Note, Double>>,
        query: String
    ): List<Note> {
        val queryKeywords = extractKeywords(query)

        val rankedResults = candidates
            .map { (note, vectorScore) ->
                val textScore = calculateEnhancedTextRelevance(
                    note.content,
                    query,
                    queryKeywords
                )
                val recencyBoost = calculateRecencyBoost(note.createdAt)

                // Адаптивные веса в зависимости от качества совпадений
                val (vectorWeight, textWeight, recencyWeight) = when {
                    vectorScore > 0.8 -> Triple(0.7, 0.2, 0.1)  // Отличное векторное совпадение
                    textScore > 0.8 -> Triple(0.3, 0.6, 0.1)    // Отличное текстовое совпадение
                    else -> Triple(0.5, 0.35, 0.15)             // Сбалансированный подход
                }

                val finalScore = (vectorWeight * vectorScore) +
                        (textWeight * textScore) +
                        (recencyWeight * recencyBoost)

                RankedNote(note, finalScore, vectorScore, textScore, recencyBoost)
            }
            .sortedByDescending { it.finalScore }

        // Логирование для отладки
        logRankingResults(rankedResults.take(FINAL_RESULTS))

        return rankedResults.take(FINAL_RESULTS).map { it.note }
    }

    private fun calculateEnhancedTextRelevance(
        content: String,
        query: String,
        queryKeywords: Set<String>
    ): Double {
        val contentLower = content.lowercase()
        val queryLower = query.lowercase()

        // Проверка точного совпадения фразы
        if (contentLower.contains(queryLower)) {
            return 1.0
        }

        // Совпадение по ключевым словам
        val keywordMatches = queryKeywords.count { keyword ->
            contentLower.contains(keyword.lowercase())
        }
        val keywordScore = if (queryKeywords.isNotEmpty()) {
            keywordMatches.toDouble() / queryKeywords.size
        } else 0.0

        // N-граммное сходство для нечеткого поиска
        val ngramScore = calculateNGramSimilarity(contentLower, queryLower)

        // Проверка синонимов (опционально)
        val synonymScore = calculateSynonymScore(contentLower, queryKeywords)

        return maxOf(
            keywordScore * 0.5 + ngramScore * 0.3 + synonymScore * 0.2,
            keywordScore,  // Гарантируем минимальный score при совпадении ключевых слов
            0.0
        )
    }

    private fun calculateNGramSimilarity(text1: String, text2: String, n: Int = 3): Double {
        if (text1.length < n || text2.length < n) return 0.0

        val ngrams1 = text1.windowed(n).toSet()
        val ngrams2 = text2.windowed(n).toSet()

        val intersection = ngrams1.intersect(ngrams2).size
        val union = ngrams1.union(ngrams2).size

        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    private fun calculateSynonymScore(content: String, queryKeywords: Set<String>): Double {
        val synonymMap = mapOf(
            "имя" to setOf("name", "зовут", "называют"),
            "name" to setOf("имя", "зовут", "называют"),
            "возраст" to setOf("age", "лет", "года"),
            "age" to setOf("возраст", "лет", "года")
        )

        var matchCount = 0
        queryKeywords.forEach { keyword ->
            val synonyms = synonymMap[keyword] ?: emptySet()
            if (synonyms.any { content.contains(it) }) {
                matchCount++
            }
        }

        return if (queryKeywords.isNotEmpty()) {
            matchCount.toDouble() / queryKeywords.size
        } else 0.0
    }

    private fun calculateRecencyBoost(createdAt: String): Double {
        return try {
            val created = Instant.parse(createdAt)
            val now = Instant.now()
            val hoursSince = Duration.between(created, now).toHours()

            when {
                hoursSince <= 24 -> 1.0      // Последние 24 часа
                hoursSince <= 168 -> 0.5     // Последняя неделя
                hoursSince <= 720 -> 0.2     // Последний месяц
                else -> 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }

    private fun extractKeywords(text: String): Set<String> {
        val stopWords = setOf(
            // Русские
            "что", "как", "где", "когда", "почему", "какой", "какая", "какие",
            "это", "эти", "тот", "та", "те", "в", "на", "с", "у", "к", "по", "для",
            "меня", "мне", "мой", "моя", "мое", "мои",
            // English
            "what", "how", "where", "when", "why", "which", "who",
            "is", "are", "was", "were", "the", "a", "an", "in", "on", "at", "to",
            "my", "me", "i", "you"
        )

        return text.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > MIN_KEYWORD_LENGTH && it !in stopWords }
            .toSet()
    }

    private fun buildOptimizedContext(notes: List<Note>, question: String): String {
        if (notes.isEmpty()) return ""

        val contextBuilder = StringBuilder()
        var estimatedTokens = 0

        // Сортируем записи по релевантности к вопросу
        val sortedNotes = notes.sortedByDescending { note ->
            calculateEnhancedTextRelevance(
                note.content,
                question,
                extractKeywords(question)
            )
        }

        for (note in sortedNotes) {
            val noteTokens = estimateTokens(note.content)

            if (estimatedTokens + noteTokens > MAX_CONTEXT_TOKENS) {
                // Если запись не влезает целиком, обрезаем
                val remainingTokens = MAX_CONTEXT_TOKENS - estimatedTokens
                if (remainingTokens > 50) { // Минимум 50 токенов для осмысленного контекста
                    val truncated = truncateToTokens(note.content, remainingTokens - 10) // -10 для "..."
                    contextBuilder.append("- $truncated...\n")
                }
                break
            }

            contextBuilder.append("- ${note.content}\n")
            estimatedTokens += noteTokens + 5 // +5 на форматирование
        }

        return contextBuilder.toString().trim()
    }

    private fun estimateTokens(text: String): Int {
        // Более точная оценка с учетом языка
        val words = text.split(Regex("\\s+"))
        val avgCharsPerToken = if (text.any { it.code > 127 }) 2.5 else 4.0 // Русский vs English
        return (text.length / avgCharsPerToken).toInt()
    }

    private fun truncateToTokens(text: String, maxTokens: Int): String {
        val avgCharsPerToken = if (text.any { it.code > 127 }) 2.5 else 4.0
        val maxChars = (maxTokens * avgCharsPerToken).toInt()

        if (text.length <= maxChars) return text

        // Обрезаем по границе слова
        val truncated = text.take(maxChars)
        val lastSpace = truncated.lastIndexOf(' ')

        return if (lastSpace > maxChars * 0.8) {
            truncated.substring(0, lastSpace)
        } else {
            truncated
        }
    }

    private fun logRankingResults(results: List<RankedNote>) {
        println("\n=== Reranking Results ===")
        results.forEachIndexed { index, result ->
            println("${index + 1}. Score: ${"%.3f".format(result.finalScore)}")
            println("   Content: ${result.note.content.take(60)}...")
            println("   Vector: ${"%.3f".format(result.vectorScore)}, " +
                    "Text: ${"%.3f".format(result.textScore)}, " +
                    "Recency: ${"%.3f".format(result.recencyScore)}")
        }
        println("========================\n")
    }

    private data class RankedNote(
        val note: Note,
        val finalScore: Double,
        val vectorScore: Double,
        val textScore: Double,
        val recencyScore: Double
    )
}