package com.vireal.api.mcp

import com.vireal.api.services.LLMService
import com.vireal.shared.models.FunctionTool
import com.vireal.shared.models.Tool
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Реестр всех доступных MCP инструментов для LLM.
 */
class ToolRegistry(private val llmService: LLMService) {

    // Определение инструментов
    val queryWithKnowledgeBaseTool = Tool(
        function = FunctionTool(
            name = "query_with_knowledge_base",
            description = "Искать ответ на вопрос пользователя в базе знаний. Использовать для общих вопросов.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("question", buildJsonObject {
                        put("type", "string")
                        put("description", "Вопрос пользователя для поиска в базе знаний.")
                    })
                })
                put("required", buildJsonArray { add("question") })
            }
        )
    )

    val createReminderTool = Tool(
        function = FunctionTool(
            name = "create_reminder",
            description = "Создать напоминание для пользователя. Использовать, когда пользователь просит напомнить о чем-то в определенное время.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Текст напоминания, например, 'позвонить маме'.")
                    })
                    put("datetime", buildJsonObject {
                        put("type", "string")
                        put("description", "Дата и время напоминания в формате ISO 8601. Например, 2025-11-19T10:00:00.")
                    })
                })
                put("required", buildJsonArray { add("description") ; add("datetime") })
            }
        )
    )

    // Список всех инструментов
    val allTools = listOf(queryWithKnowledgeBaseTool, createReminderTool)
}

