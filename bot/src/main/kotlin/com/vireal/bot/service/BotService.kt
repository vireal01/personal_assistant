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
  val mcpClient: MCPApiClient,
) {

  // === Методы для управления заметками (без изменений) ===

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

  /**
   * Подобрать нужный инструмент для задачи через MCP
   */
  suspend fun decideMCPToolToUse(
    message: String
  ): DecideMCPToolResult {
    return mcpClient.decideToolToUse(
      message = message
    )
  }

  suspend fun createNoteMCP(
    userId: Long,
    content: String,
    tags: List<String> = emptyList(),
    category: String? = null
  ): MCPToolResult {
    return mcpClient.createNote(
      userId = userId,
      content = content,
      tags = tags,
      category = category
    )
  }

  suspend fun executeConfirmedAction(
    type: MCPType,
    userId: Long,
    text: String
  ): MCPToolResult {
    return when (type) {
      MCPType.KNOWLEDGE_BASE_QUERY ->
        mcpClient.queryWithKnowledgeBase(userId, text)

      MCPType.NOTE_SAVING ->
        mcpClient.createNote(userId, text)

      MCPType.REMINDER_CREATION ->
        mcpClient.queryWithKnowledgeBase(userId, text)

      else ->
        MCPToolResult(content = emptyList(), isError = true)
    }
  }

  // === Legacy методы для обратной совместимости ===


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
