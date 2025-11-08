package com.vireal.api.services

import com.vireal.api.data.repository.NotesRepository
import com.vireal.api.data.repository.VectorSearchRepository
import com.vireal.shared.models.CreateNoteResponse
import com.vireal.shared.models.Note
import com.vireal.shared.models.SearchResult
import kotlinx.coroutines.*
import java.util.*
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
          message = "Запись добавлена (embedding создается в фоне)"
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
          message = if (embedding != null) "Запись добавлена с embedding" else "Запись добавлена без embedding"
        )
      }
    } catch (e: Exception) {
      CreateNoteResponse(
        success = false,
        message = "Ошибка: ${e.message}"
      )
    }
  }

  suspend fun createNoteWithMetadata(
    userId: Long,
    content: String,
    tags: List<String>?,
    category: String?,
    generateEmbedding: Boolean = true
  ): CreateNoteResponse {
    return try {
      // Создаем заметку
      val noteId = noteRepository.createNoteWithMetadata(userId, content, tags, category)

      // Генерируем embedding если нужно
      if (generateEmbedding) {
        val embedding = try {
          embeddingService.createEmbedding(content)
        } catch (e: Exception) {
          println("Error creating embedding: ${e.message}")
          null
        }

        if (embedding != null) {
          vectorRepository.updateEmbedding(noteId, embedding)
        }
      }

      CreateNoteResponse(
        success = true,
        noteId = noteId.toString(),
        message = "Запись добавлена с метаданными"
      )
    } catch (e: Exception) {
      CreateNoteResponse(
        success = false,
        message = "Ошибка: ${e.message}"
      )
    }
  }

  // Обработка заметок без embeddings
  suspend fun processNotesWithoutEmbeddings(userId: Long): Int {
    val notesWithoutEmbeddings = vectorRepository.getNotesWithoutEmbeddings(userId, limit = 100)

    println("Found ${notesWithoutEmbeddings.size} notes without embeddings")

    if (notesWithoutEmbeddings.isEmpty()) {
      return 0
    }

    // Обрабатываем батчами для эффективности
    val batchSize = 10
    var processedCount = 0

    notesWithoutEmbeddings.chunked(batchSize).forEach { batch ->
      val texts = batch.map { it.second }
      val embeddings = try {
        embeddingService.createEmbeddings(texts)
      } catch (e: Exception) {
        println("Error creating batch embeddings: ${e.message}")
        null
      }

      if (embeddings != null) {
        val updates = batch.zip(embeddings).map { (note, embedding) ->
          note.first to embedding
        }

        val updated = vectorRepository.updateEmbeddingsBatch(updates)
        processedCount += updated
        println("Updated $updated embeddings in batch")
      } else {
        // Если батч не удался, пробуем по одному
        for ((noteId, content) in batch) {
          val embedding = try {
            embeddingService.createEmbedding(content)
          } catch (e: Exception) {
            println("Error creating embedding for note $noteId: ${e.message}")
            null
          }

          if (embedding != null) {
            vectorRepository.updateEmbedding(noteId, embedding)
            processedCount++
            println("Updated embedding for note: $noteId")
          }
        }
      }
    }

    return processedCount
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

  // Метод для ручной генерации embedding для конкретной заметки
  suspend fun generateEmbeddingForNote(noteId: UUID): Boolean {
    val note = noteRepository.getNoteById(noteId) ?: return false

    val embedding = try {
      embeddingService.createEmbedding(note.content)
    } catch (e: Exception) {
      println("Error creating embedding for note $noteId: ${e.message}")
      null
    }

    return if (embedding != null) {
      vectorRepository.updateEmbedding(noteId, embedding)
    } else {
      false
    }
  }

  // Очистка ресурсов при остановке сервиса
  fun shutdown() {
    serviceScope.cancel()
    embeddingService.close()
  }
}
