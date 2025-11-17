package com.vireal.api.mcp

import com.vireal.api.data.repository.NotesRepository
import com.vireal.api.data.repository.VectorSearchRepository
import com.vireal.api.services.*
import com.vireal.shared.models.*
import kotlinx.serialization.json.*

/**
 * MCP сервис, предоставляющий инструменты для работы с базой знаний
 */
class MCPService(
  private val notesRepository: NotesRepository = NotesRepository(),
  private val vectorRepository: VectorSearchRepository = VectorSearchRepository(),
  private val embeddingService: EmbeddingService = EmbeddingService(),
  private val llmService: LLMService = LLMService(),
  private val hybridSearchService: HybridSearchService = HybridSearchService(),
  private val tagService: TagExtractionService = TagExtractionService()
) {

  companion object {
    const val TOOL_QUERY_WITH_CONTEXT = "query_with_knowledge_base"
    const val TOOL_QUERY_WITHOUT_CONTEXT = "query_without_context"

    private val AVAILABLE_TOOLS = setOf(TOOL_QUERY_WITH_CONTEXT, TOOL_QUERY_WITHOUT_CONTEXT)
  }

  /**
   * Получить список доступных инструментов
   */
  fun getAvailableTools(): List<MCPTool> {
    return listOf(
      MCPTool(
        name = TOOL_QUERY_WITH_CONTEXT,
        description = "Выполняет поиск в базе знаний и генерирует ответ на основе найденного контекста",
        inputSchema = buildJsonObject {
          put("type", "object")
          put("properties", buildJsonObject {
            put("userId", buildJsonObject {
              put("type", "integer")
              put("description", "ID пользователя")
            })
            put("question", buildJsonObject {
              put("type", "string")
              put("description", "Вопрос пользователя")
            })
            put("tags", buildJsonObject {
              put("type", "array")
              put("items", buildJsonObject {
                put("type", "string")
              })
              put("description", "Опциональные теги для фильтрации")
            })
            put("category", buildJsonObject {
              put("type", "string")
              put("description", "Опциональная категория для фильтрации")
            })
          })
          put("required", buildJsonArray {
            add("userId")
            add("question")
          })
        }
      ),
      MCPTool(
        name = TOOL_QUERY_WITHOUT_CONTEXT,
        description = "Генерирует ответ на вопрос без поиска в базе знаний, используя только предоставленный контекст",
        inputSchema = buildJsonObject {
          put("type", "object")
          put("properties", buildJsonObject {
            put("question", buildJsonObject {
              put("type", "string")
              put("description", "Вопрос пользователя")
            })
            put("context", buildJsonObject {
              put("type", "string")
              put("description", "Дополнительный контекст для ответа")
            })
          })
          put("required", buildJsonArray {
            add("question")
          })
        }
      )
    )
  }

  /**
   * Выполнить инструмент MCP
   */
  suspend fun executeTool(request: MCPToolRequest): MCPToolResult {
    require(request.name in AVAILABLE_TOOLS) { "Неизвестный инструмент: ${request.name}" }

    return try {
      when (request.name) {
        TOOL_QUERY_WITH_CONTEXT -> executeQueryWithKnowledgeBase(request.arguments)
        TOOL_QUERY_WITHOUT_CONTEXT -> executeQueryWithoutContext(request.arguments)
        else -> throw IllegalStateException("Достигнут недостижимый код для инструмента: ${request.name}")
      }
    } catch (e: IllegalArgumentException) {
      createErrorResult("Ошибка в параметрах запроса: ${e.message}")
    } catch (e: Exception) {
      // TODO: Добавить логирование ошибки e
      createErrorResult("Внутренняя ошибка сервера при выполнении инструмента.")
    }
  }

  /**
   * Выполнить запрос с поиском в базе знаний
   */
  private suspend fun executeQueryWithKnowledgeBase(arguments: Map<String, JsonElement>): MCPToolResult {
    val userId = arguments["userId"]?.jsonPrimitive?.longOrNull
      ?: throw IllegalArgumentException("Параметр 'userId' отсутствует или имеет неверный формат")

    val question = arguments["question"]?.jsonPrimitive?.contentOrNull
      ?: throw IllegalArgumentException("Параметр 'question' отсутствует")

    val tags = arguments["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    val category = arguments["category"]?.jsonPrimitive?.contentOrNull

    // Измеряем время поиска
    val startTime = System.currentTimeMillis()

    // Выполняем поиск с помощью HybridSearchService
    val searchResult = hybridSearchService.search(
      userId = userId,
      query = question,
      limit = 10
    )

    val searchTimeMs = System.currentTimeMillis() - startTime

    // Формируем контекст из найденных записей
    val context = buildContext(searchResult.notes, question)

    // Генерируем ответ
    val answer = if (context.isNotEmpty()) {
      llmService.generateAnswerKnowledgeBase(context, question)
    } else {
      "В базе знаний не найдено информации по вашему вопросу."
    }

    return MCPToolResult(
      content = listOf(
        MCPContent(
          type = "text",
          text = answer,
          metadata = mapOf(
            "sources_count" to JsonPrimitive(searchResult.notes.size),
            "search_time_ms" to JsonPrimitive(searchTimeMs),
            "total_found" to JsonPrimitive(searchResult.totalFound),
            "sources" to JsonArray(
              searchResult.notes.take(3).map { note ->
                JsonPrimitive("${note.content.take(100)}...")
              }
            )
          )
        )
      )
    )
  }

  /**
   * Выполнить запрос без поиска в базе знаний
   */
  private suspend fun executeQueryWithoutContext(arguments: Map<String, JsonElement>): MCPToolResult {
    val question = arguments["question"]?.jsonPrimitive?.contentOrNull
      ?: throw IllegalArgumentException("Параметр 'question' отсутствует")

    val context = arguments["context"]?.jsonPrimitive?.contentOrNull ?: ""

    // Генерируем ответ без поиска в базе
    val answer = llmService.generateAnswerRaw(context, question)

    return MCPToolResult(
      content = listOf(
        MCPContent(
          type = "text",
          text = answer,
          metadata = mapOf(
            "context_provided" to JsonPrimitive(context.isNotEmpty()),
            "context_length" to JsonPrimitive(context.length)
          )
        )
      )
    )
  }

  /**
   * Формирует контекст для LLM из найденных записей
   */
  private fun buildContext(notes: List<Note>, question: String): String {
    if (notes.isEmpty()) return ""

    val contextBuilder = StringBuilder()
    contextBuilder.append("Контекст из базы знаний:\n\n")

    notes.forEachIndexed { index, note ->
      contextBuilder.append("${index + 1}. ${note.content}")
      if (note.tags.isNotEmpty()) {
        contextBuilder.append("\nТеги: ${note.tags.joinToString(", ")}")
      }
      if (note.category != null) {
        contextBuilder.append("\nКатегория: ${note.category}")
      }
      contextBuilder.append("\n\n")
    }

    return contextBuilder.toString()
  }

  /**
   * Создать результат с ошибкой
   */
  private fun createErrorResult(message: String): MCPToolResult {
    return MCPToolResult(
      content = listOf(MCPContent("text", message)),
      isError = true
    )
  }
}
