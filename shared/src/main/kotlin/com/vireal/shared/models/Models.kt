package com.vireal.shared.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Note(
  val id: String,
  val userId: Long,
  val content: String,
  val createdAt: String,
  val tags: List<String> = emptyList(),
  val category: String? = null,
  val metadata: JsonObject = JsonObject(emptyMap()),
  val embedding: List<Float>? = null // Не возвращаем клиенту для экономии трафика
)

@Serializable
data class CreateNoteRequest(
  val userId: Long,
  val content: String,
  val tags: List<String> = emptyList(),
  val category: String? = null,
  val metadata: JsonObject? = null,
  val generateEmbedding: Boolean = true // Флаг для автоматической генерации embedding
)

@Serializable
data class UpdateNoteRequest(
  val content: String? = null,
  val tags: List<String>? = null,
  val category: String? = null,
  val metadata: JsonObject? = null,
  val regenerateEmbedding: Boolean = false // Флаг для перегенерации embedding при обновлении
)

@Serializable
data class CreateNoteResponse(
  val success: Boolean,
  val noteId: String? = null,
  val message: String
)

@Serializable
data class QueryRequest(
  val userId: Long,
  val question: String,
  val categories: List<String>? = null,
  val extraContext: String? = null,
  val tags: List<String>? = null,
  val useSemanticSearch: Boolean = true, // Использовать векторный поиск
  val topK: Int = 5 // Количество наиболее релевантных результатов
)

@Serializable
data class QueryResponse(
  val answer: String,
  val sources: List<String> = emptyList(),
  val relevanceScores: List<Float>? = null // Оценки релевантности для семантического поиска
)

@Serializable
data class SearchResult(
  val notes: List<Note>,
  val totalFound: Int,
  val facets: SearchFacets? = null
)

@Serializable
data class SearchFacets(
  val categories: Map<String, Int> = emptyMap(),
  val tags: Map<String, Int> = emptyMap()
)

// ===== MCP (Model Context Protocol) Models =====

/**
 * Представляет инструмент MCP
 */
@Serializable
data class MCPTool(
  val name: String,
  val description: String,
  val inputSchema: JsonElement
)

/**
 * Запрос на использование инструмента
 */
@Serializable
data class MCPToolRequest(
  val name: String,
  val arguments: Map<String, JsonElement>
)

/**
 * Результат выполнения инструмента
 */
@Serializable
data class MCPToolResult(
  val content: List<MCPContent>,
  val isError: Boolean = false
)

/**
 * Содержимое результата MCP
 */
@Serializable
data class MCPContent(
  val type: String,
  val text: String? = null,
  val metadata: Map<String, JsonElement>? = null
)

/**
 * Ресурс MCP
 */
@Serializable
data class MCPResource(
  val uri: String,
  val name: String,
  val description: String? = null,
  val mimeType: String? = null
)

/**
 * Содержимое ресурса
 */
@Serializable
data class MCPResourceContent(
  val uri: String,
  val mimeType: String,
  val text: String? = null,
  val blob: String? = null
)

/**
 * Упрощенные запросы для MCP эндпоинтов
 */
@Serializable
data class MCPQueryWithContextRequest(
  val userId: Long,
  val question: String,
  val tags: List<String> = emptyList(),
  val category: String? = null
)

@Serializable
data class MCPQueryWithoutContextRequest(
    val question: String,
    val context: String = ""
)

// ===== OpenAI Tool Calling Models =====

@Serializable
data class Tool(
    val type: String = "function",
    val function: FunctionTool
)

@Serializable
data class FunctionTool(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ToolChoice(
    val type: String = "function",
    val function: FunctionChoice
)

@Serializable
data class FunctionChoice(
    val name: String
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String,
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String // Аргументы приходят как строка JSON
)
