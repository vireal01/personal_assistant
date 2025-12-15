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
   * Определить, какой MCP Tool необходимо использовать
   */
  suspend fun decideToolToUse(
    message: String,
  ): DecideMCPToolResult {
    return try {
      val response = client.post("$baseUrl/api/mcp/intent/decide") {
        contentType(ContentType.Application.Json)
        setBody(
          MCPDecideToolRequest(
            message = message,
          )
        )
      }

      if (response.status.isSuccess()) {
        response.body<DecideMCPToolResult>()
      } else {
        logger.error("Failed to choose a tool: ${response.status}")
        DecideMCPToolResult(
          type = MCPType.UNCATEGORIZED,
          isError = true
        )
      }
    } catch (e: Exception) {
      logger.error("Failed to choose a tool", e)
      DecideMCPToolResult(
        type = MCPType.UNCATEGORIZED,
        isError = true
      )
    }
  }

  suspend fun createNote(
    userId: Long,
    content: String,
    tags: List<String> = emptyList(),
    category: String? = null
  ): MCPToolResult {
    return try {
      val response = client.post("$baseUrl/api/mcp/tool/save-note") {
        contentType(ContentType.Application.Json)
        setBody(
          MCPSaveNoteRequest(
            userId = userId,
            text = content,
            tags = tags,
            category = category
          )
        )
      }

      if (response.status.isSuccess()) {
        response.body<MCPToolResult>()
      } else {
        MCPToolResult.errText(text = "Server error: ${response.status}")
      }
    } catch (e: Exception) {
      logger.error("Error creating note", e)
      MCPToolResult.errText(text = e.message ?: "Unknown error")
    }
  }
}

