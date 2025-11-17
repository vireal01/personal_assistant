package com.vireal.bot.mcp

import com.vireal.shared.models.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * MCP клиент для взаимодействия с MCP API
 */
class MCPApiClient(
  private val baseUrl: String = "http://api:8080"
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

  companion object {
    const val TOOL_QUERY_WITH_CONTEXT = "query_with_knowledge_base"
    const val TOOL_QUERY_WITHOUT_CONTEXT = "query_without_context"
  }

  /**
   * Получить список доступных MCP инструментов
   */
  suspend fun getAvailableTools(): List<MCPTool> {
    return try {
      val response = client.get("$baseUrl/api/mcp/tools")
      if (response.status.isSuccess()) {
        response.body<List<MCPTool>>()
      } else {
        logger.error("Failed to get tools: ${response.status}")
        emptyList()
      }
    } catch (e: Exception) {
      logger.error("Error getting MCP tools", e)
      emptyList()
    }
  }

  /**
   * Выполнить MCP инструмент
   */
  suspend fun executeTool(request: MCPToolRequest): MCPToolResult {
    return try {
      val response = client.post("$baseUrl/api/mcp/tools/execute") {
        contentType(ContentType.Application.Json)
        setBody(request)
      }

      if (response.status.isSuccess()) {
        response.body<MCPToolResult>()
      } else {
        logger.error("Failed to execute tool: ${response.status}")
        MCPToolResult(
          content = listOf(MCPContent("text", "Ошибка сервера: ${response.status}")),
          isError = true
        )
      }
    } catch (e: Exception) {
      logger.error("Error executing MCP tool", e)
      MCPToolResult(
        content = listOf(MCPContent("text", "Ошибка: ${e.message}")),
        isError = true
      )
    }
  }

  /**
   * Запрос с поиском в базе знаний
   */
  suspend fun queryWithKnowledgeBase(
    userId: Long,
    question: String,
    tags: List<String> = emptyList(),
    category: String? = null
  ): MCPToolResult {
    return try {
      val response = client.post("$baseUrl/api/mcp/query/with-context") {
        contentType(ContentType.Application.Json)
        setBody(
          MCPQueryWithContextRequest(
            userId = userId,
            question = question,
            tags = tags,
            category = category
          )
        )
      }

      if (response.status.isSuccess()) {
        response.body<MCPToolResult>()
      } else {
        logger.error("Failed to query with context: ${response.status}")
        MCPToolResult(
          content = listOf(MCPContent("text", "Ошибка сервера при поиске")),
          isError = true
        )
      }
    } catch (e: Exception) {
      logger.error("Error querying with knowledge base", e)
      MCPToolResult(
        content = listOf(MCPContent("text", "Ошибка: ${e.message}")),
        isError = true
      )
    }
  }

  /**
   * Запрос без поиска в базе знаний
   */
  suspend fun queryWithoutKnowledgeBase(
    question: String,
    context: String = ""
  ): MCPToolResult {
    return try {
      val response = client.post("$baseUrl/api/mcp/query/without-context") {
        contentType(ContentType.Application.Json)
        setBody(
          MCPQueryWithoutContextRequest(
            question = question,
            context = context
          )
        )
      }

      if (response.status.isSuccess()) {
        response.body<MCPToolResult>()
      } else {
        logger.error("Failed to query without context: ${response.status}")
        MCPToolResult(
          content = listOf(MCPContent("text", "Ошибка сервера")),
          isError = true
        )
      }
    } catch (e: Exception) {
      logger.error("Error querying without knowledge base", e)
      MCPToolResult(
        content = listOf(MCPContent("text", "Ошибка: ${e.message}")),
        isError = true
      )
    }
  }

  /**
   * Создать MCP запрос с параметрами
   */
  private fun createToolRequest(
    toolName: String,
    userId: Long? = null,
    question: String? = null,
    context: String? = null,
    tags: List<String>? = null,
    category: String? = null
  ): MCPToolRequest {
    val arguments = buildMap<String, JsonElement> {
      userId?.let { put("userId", JsonPrimitive(it)) }
      question?.let { put("question", JsonPrimitive(it)) }
      context?.let { put("context", JsonPrimitive(it)) }
      tags?.takeIf { it.isNotEmpty() }?.let {
        put("tags", JsonArray(it.map { tag -> JsonPrimitive(tag) }))
      }
      category?.let { put("category", JsonPrimitive(it)) }
    }

    return MCPToolRequest(
      name = toolName,
      arguments = arguments
    )
  }

  fun close() {
    client.close()
  }
}

