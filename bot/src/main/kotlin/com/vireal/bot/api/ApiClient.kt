package com.vireal.bot.api

import com.vireal.shared.models.CreateNoteRequest
import com.vireal.shared.models.CreateNoteResponse
import com.vireal.shared.models.Note
import com.vireal.shared.models.QueryRequest
import com.vireal.shared.models.QueryResponse
import com.vireal.shared.models.SearchResult
import com.vireal.shared.models.UpdateNoteRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class ApiClient(
  val baseUrl: String = "http://api:8080"
) {
  private val logger = LoggerFactory.getLogger(this::class.java)

  private val client = HttpClient(CIO) {
    install(ContentNegotiation) {
      json(Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
      })
    }
    install(Logging) {
      logger = Logger.DEFAULT
      level = LogLevel.INFO
    }
    expectSuccess = false
  }

  suspend fun createNote(
    userId: Long,
    content: String,
    tags: List<String> = emptyList(),
    category: String? = null
  ): CreateNoteResponse {
    return try {
      val response = client.post("$baseUrl/api/notes") {
        contentType(ContentType.Application.Json)
        setBody(
          CreateNoteRequest(
            userId = userId,
            content = content,
            tags = tags,
            category = category
          )
        )
      }

      if (response.status.isSuccess()) {
        response.body<CreateNoteResponse>()
      } else {
        CreateNoteResponse(
          success = false,
          message = "Server error: ${response.status}"
        )
      }
    } catch (e: Exception) {
      logger.error("Error creating note", e)
      CreateNoteResponse(
        success = false,
        message = e.message ?: "Unknown error"
      )
    }
  }

  suspend fun searchNotes(
    userId: Long,
    query: String,
    limit: Int = 5
  ): SearchResult {
    return try {
      val response = client.get("$baseUrl/api/notes/search") {
        parameter("userId", userId)
        parameter("query", query)
        parameter("limit", limit)
      }

      if (response.status.isSuccess()) {
        response.body<SearchResult>()
      } else {
        SearchResult(notes = emptyList(), totalFound = 0)
      }
    } catch (e: Exception) {
      logger.error("Error searching notes", e)
      SearchResult(notes = emptyList(), totalFound = 0)
    }
  }


  suspend fun getUserNotes(userId: Long, limit: Int = 10): List<Note> {
    return try {
      val response = client.get("$baseUrl/api/notes/user/$userId") {
        parameter("limit", limit)
      }

      if (response.status.isSuccess()) {
        response.body<List<Note>>()
      } else {
        emptyList()
      }
    } catch (e: Exception) {
      logger.error("Error getting user notes", e)
      emptyList()
    }
  }

  suspend fun getNoteById(noteId: String): Note? {
    return try {
      val response = client.get("$baseUrl/api/notes/$noteId")

      if (response.status.isSuccess()) {
        response.body<Note>()
      } else {
        null
      }
    } catch (e: Exception) {
      logger.error("Error getting note by id", e)
      null
    }
  }

  suspend fun updateNote(
    noteId: String,
    content: String? = null,
    tags: List<String>? = null,
    category: String? = null,
    regenerateEmbedding: Boolean = false
  ): Boolean {
    return try {
      val response = client.put("$baseUrl/api/notes/$noteId") {
        contentType(ContentType.Application.Json)
        setBody(
          UpdateNoteRequest(
            content = content,
            tags = tags,
            category = category,
            regenerateEmbedding = regenerateEmbedding
          )
        )
      }

      response.status.isSuccess()
    } catch (e: Exception) {
      logger.error("Error updating note", e)
      false
    }
  }

  suspend fun deleteNote(noteId: String): Boolean {
    return try {
      val response = client.delete("$baseUrl/api/notes/$noteId")
      response.status.isSuccess()
    } catch (e: Exception) {
      logger.error("Error deleting note", e)
      false
    }
  }

  suspend fun getUserTags(userId: Long): Set<String> {
    return try {
      val response = client.get("$baseUrl/api/notes/user/$userId/tags")

      if (response.status.isSuccess()) {
        response.body<Set<String>>()
      } else {
        emptySet()
      }
    } catch (e: Exception) {
      logger.error("Error getting user tags", e)
      emptySet()
    }
  }

  suspend fun getNotesByTag(userId: Long, tag: String, limit: Int = 50): List<Note> {
    return try {
      val response = client.get("$baseUrl/api/notes/tags") {
        parameter("userId", userId)
        parameter("tags", tag)
        parameter("limit", limit)
      }

      if (response.status.isSuccess()) {
        response.body<List<Note>>()
      } else {
        emptyList()
      }
    } catch (e: Exception) {
      logger.error("Error getting notes by tag", e)
      emptyList()
    }
  }

  suspend fun getNotesByCategory(userId: Long, category: String, limit: Int = 50): List<Note> {
    return try {
      val response = client.get("$baseUrl/api/notes/category") {
        parameter("userId", userId)
        parameter("category", category)
        parameter("limit", limit)
      }

      if (response.status.isSuccess()) {
        response.body<List<Note>>()
      } else {
        emptyList()
      }
    } catch (e: Exception) {
      logger.error("Error getting notes by category", e)
      emptyList()
    }
  }

  suspend fun getCategoryStats(userId: Long): Map<String, Int> {
    return try {
      val response = client.get("$baseUrl/api/notes/user/$userId/stats/categories")

      if (response.status.isSuccess()) {
        response.body<Map<String, Int>>()
      } else {
        emptyMap()
      }
    } catch (e: Exception) {
      logger.error("Error getting category stats", e)
      emptyMap()
    }
  }

  suspend fun getNotesCount(userId: Long): Long {
    return try {
      val response = client.get("$baseUrl/api/notes/user/$userId/count")

      if (response.status.isSuccess()) {
        response.body<Long>()
      } else {
        0L
      }
    } catch (e: Exception) {
      logger.error("Error getting notes count", e)
      0L
    }
  }

  suspend fun findSimilarNotes(userId: Long, noteId: String, limit: Int = 5): List<Note> {
    return try {
      val response = client.get("$baseUrl/api/notes/$noteId/similar") {
        parameter("userId", userId)
        parameter("limit", limit)
      }

      if (response.status.isSuccess()) {
        response.body<List<Note>>()
      } else {
        emptyList()
      }
    } catch (e: Exception) {
      logger.error("Error finding similar notes", e)
      emptyList()
    }
  }

  suspend fun processEmbeddings(userId: Long): Int {
    return try {
      val response = client.post("$baseUrl/api/notes/user/$userId/embeddings/process")

      if (response.status.isSuccess()) {
        response.body<Int>()
      } else {
        0
      }
    } catch (e: Exception) {
      logger.error("Error processing embeddings", e)
      0
    }
  }

  fun close() {
    client.close()
  }
}
