package com.vireal.api.mcp

import com.vireal.shared.models.MCPToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

/**
 * Простой пример использования MCP API
 */
fun main() {
    runBlocking {
        val mcpService = MCPService()

        println("=== MCP API Example ===")

        // Получить список доступных инструментов
        println("\n1. Доступные инструменты:")
        val tools = mcpService.getAvailableTools()
        tools.forEach { tool ->
            println("   - ${tool.name}: ${tool.description}")
        }

        // Пример запроса с поиском в базе знаний
        println("\n2. Запрос с поиском в базе знаний:")
        val queryWithContextRequest = MCPToolRequest(
            name = MCPService.TOOL_QUERY_WITH_CONTEXT,
            arguments = mapOf(
                "userId" to JsonPrimitive(1L),
                "question" to JsonPrimitive("Как настроить Docker?"),
                "tags" to JsonArray(listOf(
                    JsonPrimitive("docker"),
                    JsonPrimitive("настройка")
                ))
            )
        )

        val result1 = mcpService.executeTool(queryWithContextRequest)
        println("Результат: ${result1.content.firstOrNull()?.text}")
        println("Метаданные: ${result1.content.firstOrNull()?.metadata}")

        // Пример запроса без поиска в базе знаний
        println("\n3. Запрос без поиска в базе знаний:")
        val queryWithoutContextRequest = MCPToolRequest(
            name = MCPService.TOOL_QUERY_WITHOUT_CONTEXT,
            arguments = mapOf(
                "question" to JsonPrimitive("Что делает этот код?"),
                "context" to JsonPrimitive("function hello() { console.log('Hello World'); }")
            )
        )

        val result2 = mcpService.executeTool(queryWithoutContextRequest)
        println("Результат: ${result2.content.firstOrNull()?.text}")

        // Пример ошибочного запроса
        println("\n4. Пример ошибочного запроса:")
        val errorRequest = MCPToolRequest(
            name = "nonexistent_tool",
            arguments = emptyMap()
        )

        val result3 = mcpService.executeTool(errorRequest)
        println("Ошибка: ${result3.content.firstOrNull()?.text}")
        println("Это ошибка: ${result3.isError}")
    }
}

/**
 * Пример использования MCP через HTTP API
 */
object MCPAPIExample {

    /**
     * Пример запроса к эндпоинту с поиском в базе знаний
     */
    fun queryWithKnowledgeBaseExample(): String {
        return """
        POST /api/mcp/query/with-context
        Content-Type: application/json

        {
          "userId": 123,
          "question": "Как установить Kubernetes в production?",
          "tags": ["kubernetes", "production", "установка"],
          "category": "devops"
        }

        Ожидаемый ответ:
        {
          "content": [
            {
              "type": "text",
              "text": "Для установки Kubernetes в production среде рекомендуется...",
              "metadata": {
                "sources_count": 5,
                "search_time_ms": 120,
                "total_found": 15,
                "sources": [
                  "Kubernetes production guide...",
                  "Best practices для K8s...",
                  "Security considerations..."
                ]
              }
            }
          ],
          "isError": false
        }
        """.trimIndent()
    }

    /**
     * Пример запроса без поиска в базе знаний
     */
    fun queryWithoutKnowledgeBaseExample(): String {
        return """
        POST /api/mcp/query/without-context
        Content-Type: application/json

        {
          "question": "Объясни этот код на русском языке",
          "context": "const users = await db.users.findMany({ where: { active: true } });"
        }

        Ожидаемый ответ:
        {
          "content": [
            {
              "type": "text",
              "text": "Этот код асинхронно получает всех активных пользователей из базы данных...",
              "metadata": {
                "context_provided": true,
                "context_length": 78
              }
            }
          ],
          "isError": false
        }
        """.trimIndent()
    }

    /**
     * Пример использования legacy API (обратная совместимость)
     */
    fun legacyAPIExample(): String {
        return """
        // Старый способ (продолжает работать)
        POST /api/query
        Content-Type: application/json

        {
          "userId": 123,
          "question": "Как настроить CI/CD?"
        }

        Ответ в старом формате:
        {
          "answer": "Для настройки CI/CD рекомендуется...",
          "sources": [
            "CI/CD best practices...",
            "GitLab CI configuration..."
          ]
        }
        """.trimIndent()
    }
}
