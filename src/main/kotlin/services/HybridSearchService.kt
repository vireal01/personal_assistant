package com.vireal.services

import com.vireal.data.models.*
import com.vireal.data.repository.NotesRepository
import com.vireal.data.repository.VectorSearchRepository

class HybridSearchService(
    private val notesRepository: NotesRepository = NotesRepository(),
    private val vectorRepository: VectorSearchRepository = VectorSearchRepository(),
    private val embeddingService: EmbeddingService = EmbeddingService()
) {

    suspend fun search(userId: Long, query: String, limit: Int = 5): SearchResult {
        println("Starting hybrid search for query: '$query'")

        // 1. Создаем embedding для запроса
        val queryEmbedding = embeddingService.createEmbedding(query)

        val notes = if (queryEmbedding != null) {
            // 2. Векторный поиск
            val vectorResults = vectorRepository.searchByVectorWithFilters(
                userId = userId,
                queryEmbedding = queryEmbedding,
                limit = limit,
                threshold = 0.5
            )

            println("Vector search found ${vectorResults.size} results")

            if (vectorResults.isNotEmpty()) {
                // Возвращаем результаты векторного поиска
                vectorResults.map { it.first }
            } else {
                // 3. Fallback на текстовый поиск
                println("No vector results, falling back to text search")
                notesRepository.searchNotes(userId, query, limit)
            }
        } else {
            // Если embedding недоступен, используем только текстовый поиск
            println("Embedding not available, using text search only")
            notesRepository.searchNotes(userId, query, limit)
        }

        return SearchResult(
            notes = notes,
            totalFound = notes.size
        )
    }

    suspend fun processNotesWithoutEmbeddings(userId: Long) {
        val notesWithoutEmbeddings = vectorRepository.getNotesWithoutEmbeddings(userId)

        println("Found ${notesWithoutEmbeddings.size} notes without embeddings")

        for ((noteId, content) in notesWithoutEmbeddings) {
            val embedding = embeddingService.createEmbedding(content)
            if (embedding != null) {
                vectorRepository.updateEmbedding(noteId, embedding)
                println("Updated embedding for note: $noteId")
            }
        }
    }
}