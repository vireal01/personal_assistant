package com.vireal.bot.service

import com.vireal.bot.api.ApiClient
import com.vireal.bot.mcp.MCPApiClient
import com.vireal.shared.models.*

/**
 * BotService с поддержкой MCP архитектуры
 * Использует MCP для AI запросов и legacy API для управления заметками
 */
class BotService(
  private val apiClient: ApiClient,
  val mcpClient: MCPApiClient = MCPApiClient(apiClient.baseUrl)
) {

  // === Методы для управления заметками (без изменений) ===

  suspend fun createNote(userId: Long, content: String): CreateNoteResponse {
    return apiClient.createNote(userId, content)
  }

  suspend fun searchNotes(userId: Long, query: String, limit: Int = 5): SearchResult {
    return apiClient.searchNotes(userId, query, limit)
  }

  // === MCP методы для AI запросов ===

  /**
   * Запрос с поиском в базе знаний через MCP
   */
  suspend fun askQuestionWithKnowledgeBaseMCP(
    userId: Long,
    question: String,
    tags: List<String> = emptyList(),
    category: String? = null
  ): MCPToolResult {
    return mcpClient.queryWithKnowledgeBase(
      userId = userId,
      question = question,
      tags = tags,
      category = category
    )
  }

  /**
   * Запрос без поиска в базе знаний через MCP
   */
  suspend fun askQuestionWithoutKnowledgeBaseMCP(
    question: String,
    context: String = ""
  ): MCPToolResult {
    return mcpClient.queryWithoutKnowledgeBase(
      question = question,
      context = context
    )
  }

  // === Legacy методы для обратной совместимости ===

  suspend fun askQuestionWithKnowledgeBaseContext(userId: Long, question: String): QueryResponse {
    val mcpResult = askQuestionWithKnowledgeBaseMCP(userId, question)
    return mcpClient.convertToLegacyResponse(mcpResult)
  }

  suspend fun askQuestionWithNoKnowledgeBaseContext(userId: Long, question: String, context: String): QueryResponse {
    val mcpResult = askQuestionWithoutKnowledgeBaseMCP(question, context)
    return mcpClient.convertToLegacyResponse(mcpResult)
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
