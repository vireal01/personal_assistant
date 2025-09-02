package com.vireal.services

import com.vireal.data.models.*
import com.vireal.data.repository.NotesRepository
import com.vireal.data.repository.VectorSearchRepository

class NotesService(
    private val repository: NotesRepository = NotesRepository(),
    private val vectorRepository: VectorSearchRepository = VectorSearchRepository(),
    private val embeddingService: EmbeddingService = EmbeddingService()
) {

    suspend fun addNote(userId: Long, content: String): CreateNoteResponse {
        return try {
            println("=== NotesService.addNote called ===")
            println("userId: $userId, content: '${content.take(50)}...'")

            // 1. Создаем запись
            val noteId = repository.createNote(userId, content)
            println("Note created with ID: $noteId")

            // 2. Создаем embedding
            println("Creating embedding...")
            val embedding = embeddingService.createEmbedding(content)

            if (embedding != null) {
                println("Embedding created, size: ${embedding.size}")
                val updated = vectorRepository.updateEmbedding(noteId, embedding)
                println("Embedding saved to DB: $updated")
            } else {
                println("WARNING: Embedding is null!")
            }

            CreateNoteResponse(
                success = true,
                noteId = noteId.toString(),
                message = "Запись успешно добавлена"
            )
        } catch (e: Exception) {
            println("ERROR in addNote: ${e.message}")
            e.printStackTrace()
            CreateNoteResponse(
                success = false,
                message = "Ошибка: ${e.message}"
            )
        }
    }

    suspend fun searchNotes(userId: Long, query: String): SearchResult {
        val hybridSearch = HybridSearchService(repository, vectorRepository, embeddingService)
        return hybridSearch.search(userId, query)
    }

    suspend fun getUserNotes(userId: Long): List<Note> {
        return repository.getUserNotes(userId)
    }
}