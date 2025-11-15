package com.vireal.api.controllers

import com.vireal.api.mcp.MCPService
import com.vireal.shared.models.*
import kotlinx.serialization.json.*

/**
 * MCP контроллер для обработки запросов через MCP архитектуру
 */
class MCPController {
    private val mcpService = MCPService()

    /**
     * Обработка запроса с поиском в базе знаний через MCP
     */
    suspend fun queryWithKnowledgeBase(
        userId: Long,
        question: String,
        tags: List<String> = emptyList(),
        category: String? = null
    ): QueryResponse {
        val request = MCPToolRequest(
            name = MCPService.TOOL_QUERY_WITH_CONTEXT,
            arguments = buildMap {
                put("userId", JsonPrimitive(userId))
                put("question", JsonPrimitive(question))
                if (tags.isNotEmpty()) {
                    put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
                }
                if (category != null) {
                    put("category", JsonPrimitive(category))
                }
            }
        )

        val result = mcpService.executeTool(request)

        return if (result.isError) {
            QueryResponse(
                answer = result.content.firstOrNull()?.text ?: "Произошла ошибка при обработке запроса",
                sources = emptyList()
            )
        } else {
            val content = result.content.firstOrNull()
            val metadata = content?.metadata

            QueryResponse(
                answer = content?.text ?: "Не удалось получить ответ",
                sources = metadata?.get("sources")?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList()
            )
        }
    }

    /**
     * Обработка запроса без поиска в базе знаний через MCP
     */
    suspend fun queryWithoutKnowledgeBase(
        question: String,
        context: String = ""
    ): QueryResponse {
        val request = MCPToolRequest(
            name = MCPService.TOOL_QUERY_WITHOUT_CONTEXT,
            arguments = mapOf(
                "question" to JsonPrimitive(question),
                "context" to JsonPrimitive(context)
            )
        )

        val result = mcpService.executeTool(request)

        return if (result.isError) {
            QueryResponse(
                answer = result.content.firstOrNull()?.text ?: "Произошла ошибка при обработке запроса",
                sources = emptyList()
            )
        } else {
            QueryResponse(
                answer = result.content.firstOrNull()?.text ?: "Не удалось получить ответ",
                sources = emptyList()
            )
        }
    }

    /**
     * Получить список доступных MCP инструментов
     */
    fun getAvailableTools() = mcpService.getAvailableTools()

    /**
     * Выполнить произвольный MCP инструмент
     */
    suspend fun executeTool(request: MCPToolRequest) = mcpService.executeTool(request)
}
