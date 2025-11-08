package com.vireal.bot.service

import com.vireal.bot.api.ApiClient
import com.vireal.shared.models.CreateNoteResponse
import com.vireal.shared.models.Note
import com.vireal.shared.models.QueryResponse
import com.vireal.shared.models.SearchResult
import org.slf4j.LoggerFactory

class BotService(private val apiClient: ApiClient) {
  private val logger = LoggerFactory.getLogger(this::class.java)

  suspend fun createNote(userId: Long, content: String): CreateNoteResponse {
    return apiClient.createNote(userId, content)
  }

  suspend fun searchNotes(userId: Long, query: String, limit: Int = 5): SearchResult {
    return apiClient.searchNotes(userId, query, limit)
  }

  suspend fun askQuestion(userId: Long, question: String): QueryResponse {
    return apiClient.askQuestion(userId, question)
  }

  suspend fun getUserNotes(userId: Long, limit: Int = 10): List<Note> {
    return apiClient.getUserNotes(userId, limit)
  }

  suspend fun getUserTags(userId: Long): Set<String> {
    return apiClient.getUserTags(userId)
  }

  suspend fun getNotesByTag(userId: Long, tag: String): List<Note> {
    return apiClient.getNotesByTag(userId, tag)
  }

  suspend fun getNotesByCategory(userId: Long, category: String): List<Note> {
    return apiClient.getNotesByCategory(userId, category)
  }

  suspend fun getCategoryStats(userId: Long): Map<String, Int> {
    return apiClient.getCategoryStats(userId)
  }

  suspend fun getNotesCount(userId: Long): Long {
    return apiClient.getNotesCount(userId)
  }

  suspend fun findSimilarNotes(userId: Long, noteId: String): List<Note> {
    return apiClient.findSimilarNotes(userId, noteId)
  }

  suspend fun deleteNote(noteId: String): Boolean {
    return apiClient.deleteNote(noteId)
  }

  suspend fun processEmbeddings(userId: Long): Int {
    return apiClient.processEmbeddings(userId)
  }
}
