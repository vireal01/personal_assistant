package com.vireal.api.mcp

import com.vireal.api.services.LLMService
import com.vireal.shared.models.MCPType
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
    name = MCPType.KNOWLEDGE_BASE_QUERY.name,
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

  val createReminderTool = Tool(
    name = MCPType.REMINDER_CREATION.name,
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
      put("required", buildJsonArray { add("description"); add("datetime") })
    }
  )

  // Новый инструмент: сохранить заметку
  val saveNoteTool = Tool(
    name = MCPType.NOTE_SAVING.name,
    description = "Сохранить заметку пользователя. Использовать, когда ввод выглядит как факт/событие для записи в журнал.",
    parameters = buildJsonObject {
      put("type", "object")
      put("properties", buildJsonObject {
        put("text", buildJsonObject {
          put("type", "string")
          put("description", "Текст заметки, который нужно сохранить.")
        })
        put("userId", buildJsonObject {
          put("type", "number")
          put("description", "Идентификатор пользователя, для которого сохраняется заметка.")
        })
        put("tags", buildJsonObject {
          put("type", "array")
          put("items", buildJsonObject { put("type", "string") })
          put("description", "Список тегов для заметки (необязательно).")
        })
        put("category", buildJsonObject {
          put("type", "string")
          put("description", "Категория заметки (необязательно).")
        })
      })
      put("required", buildJsonArray { add("text"); add("userId") })
    }
  )

  // Определить тип требуемого инструмента
  val decideToolTool = Tool(
    name = "DECIDE_MCP_TOOL",
    description = "Определить, какой MCP инструмент использовать на основе входного сообщения пользователя.",
    parameters = buildJsonObject {
      put("type", "object")
      put("properties", buildJsonObject {
        put("message", buildJsonObject {
          put("type", "string")
          put("description", "Входное сообщение пользователя для анализа.")
        })
      })
      put("required", buildJsonArray { add("message") })
    }
  )

  // Список всех инструментов
  val allTools = listOf(queryWithKnowledgeBaseTool, createReminderTool, saveNoteTool, decideToolTool)
}
