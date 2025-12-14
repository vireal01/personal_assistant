package com.vireal.api.services

import com.vireal.api.data.repository.NotesRepository
import com.vireal.api.data.repository.VectorSearchRepository
import kotlinx.coroutines.*
import com.vireal.shared.models.CreateNoteResponse
import com.vireal.shared.models.Note
import com.vireal.shared.models.SearchResult
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class NotesService(
  private val noteRepository: NotesRepository = NotesRepository(),
  private val vectorRepository: VectorSearchRepository = VectorSearchRepository(),
  private val embeddingService: EmbeddingService = EmbeddingService(),
  private val tagService: TagExtractionService = TagExtractionService()
) {

  // Корутин скоуп для фоновых задач
  private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Очередь для асинхронной обработки embeddings
  private val embeddingQueue = ConcurrentLinkedQueue<Pair<UUID, String>>()

  private val successMessage =  "✅ Запись успешно добавлена"

  suspend fun addNote(userId: Long, content: String): CreateNoteResponse {
    return try {
      // 1. Извлекаем теги и категорию
      val (tags, category) = tagService.extractTagsAndCategory(content)

      // 2. Создаем заметку в БД
      val noteId = noteRepository.createNoteWithMetadata(userId, content, tags, category)

      // 3. Генерируем embedding синхронно или асинхронно
      val generateAsync = System.getenv("ASYNC_EMBEDDING")?.toBoolean() ?: false

      if (generateAsync) {
        // Асинхронная генерация в фоне
        serviceScope.launch {
          try {
            val embedding = embeddingService.createEmbedding(content)
            if (embedding != null) {
              vectorRepository.updateEmbedding(noteId, embedding)
              println("Embedding updated asynchronously for note: $noteId")
            }
          } catch (e: Exception) {
            println("Error creating embedding for note $noteId: ${e.message}")
          }
        }

        CreateNoteResponse(
          success = true,
          noteId = noteId.toString(),
          message = successMessage
        )
      } else {
        // Синхронная генерация - ждем результат
        val embedding = try {
          embeddingService.createEmbedding(content)
        } catch (e: Exception) {
          println("Error creating embedding: ${e.message}")
          null
        }

        if (embedding != null) {
          vectorRepository.updateEmbedding(noteId, embedding)
        }

        CreateNoteResponse(
          success = true,
          noteId = noteId.toString(),
          message = successMessage
        )
      }
    } catch (e: Exception) {
      CreateNoteResponse(
        success = false,
        message = "Ошибка: ${e.message}"
      )
    }
  }

  suspend fun searchNotes(userId: Long, query: String): SearchResult {
    val hybridSearch = HybridSearchService(noteRepository, vectorRepository, embeddingService)
    return hybridSearch.search(userId, query)
  }

  suspend fun getUserNotes(userId: Long): List<Note> {
    return noteRepository.getUserNotes(userId)
  }

  // Обновление заметки с перегенерацией embedding
  suspend fun updateNote(
    noteId: UUID,
    content: String? = null,
    tags: List<String>? = null,
    category: String? = null,
    regenerateEmbedding: Boolean = false
  ): Boolean {
    val updated = noteRepository.updateNote(noteId, content, tags, category)

    if (updated && regenerateEmbedding && content != null) {
      val embedding = try {
        embeddingService.createEmbedding(content)
      } catch (e: Exception) {
        println("Error regenerating embedding: ${e.message}")
        null
      }

      if (embedding != null) {
        vectorRepository.updateEmbedding(noteId, embedding)
      }
    }

    return updated
  }
}
