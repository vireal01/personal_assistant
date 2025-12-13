package com.vireal.api.mcp

import com.vireal.api.services.HybridSearchService
import com.vireal.api.services.LLMService
import com.vireal.shared.models.DecideMCPToolResult
import com.vireal.shared.models.MCPContent
import com.vireal.shared.models.MCPDecideToolRequest
import com.vireal.shared.models.MCPTool
import com.vireal.shared.models.MCPToolRequest
import com.vireal.shared.models.MCPToolResult
import com.vireal.shared.models.MCPType
import com.vireal.shared.models.Note
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP сервис, который использует LLM для выбора и вызова инструментов.
 */
class MCPService(
  private val llmService: LLMService = LLMService(),
  private val hybridSearchService: HybridSearchService = HybridSearchService(),
  // private val reminderService: ReminderService = ReminderService() // Будет добавлено в будущем
) {
  private val toolRegistry = ToolRegistry(llmService)
  private val json = Json { ignoreUnknownKeys = true }

  // Простейшее состояние ожидающего подтверждения действия (MVP)
  private data class PendingAction(
    val id: String,
    val userId: Long,
    val toolName: String,
    val arguments: JsonObject,
    val createdAt: Long = System.currentTimeMillis()
  )

  private val pendingActionsByUser: MutableMap<Long, PendingAction> = ConcurrentHashMap()

  // Простейшее хранилище заметок (MVP), чтобы завершить флоу
  private val notesByUser: MutableMap<Long, MutableList<Note>> = ConcurrentHashMap()

  /**
   * Вернуть список доступных MCP инструментов для клиента (упрощенная схема).
   */
  fun getAvailableTools(): List<MCPTool> {
    println("[MCPService] getAvailableTools() called")
    val tools = toolRegistry.allTools.map { tool ->
      MCPTool(
        name = tool.name,
        description = tool.description,
        mcpType = parseMCPType(tool.name),
        inputSchema = tool.parameters,
      )
    }
    println("[MCPService] getAvailableTools() -> ${tools.map { it.name }}")
    return tools
  }

  /**
   * Определить, какой MCP Tool необходимо использовать
   */
  suspend fun decideToolToUse(
    request: MCPDecideToolRequest,
  ): DecideMCPToolResult {
    val message = request.message
    println("[MCPService] decideToolToUse(message=\"$message\") called")
    val llmDecision = llmService.decideToolToUse(userMessage = message, tools = toolRegistry.allTools)
    println("[MCPService] decideToolToUse -> LLM decision: tool_calls=${llmDecision?.tool_calls}, content=${llmDecision?.content}")

    if (llmDecision?.content == null) {
      println("[MCPService] decideToolToUse -> LLM decision is null")
      return DecideMCPToolResult(isError = true, type = MCPType.UNCATEGORIZED)
    }

    val type = parseMCPType(llmDecision)
    println("[MCPService] decideToolToUse -> parsed MCPType: $type")

    return DecideMCPToolResult(
      isError = false,
      type = type,
    )
  }


  /**
   * Выполнить инструмент по имени и аргументам.
   */
  suspend fun executeTool(request: MCPToolRequest): MCPToolResult {
    println("[MCPService] executeTool(name=${request.type.name}, args=${request.arguments.keys}) called")
    val result = when (request.type) {
      MCPType.KNOWLEDGE_BASE_QUERY -> {
        val question = (request.arguments["question"] as? JsonPrimitive)?.content
        val userId = (request.arguments["userId"] as? JsonPrimitive)?.longOrNull
        if (question.isNullOrBlank() || userId == null) {
          createErrorResult("Требуются параметры: userId (Long) и question (String)")
        } else {
          queryWithKnowledgeBase(userId = userId, question = question)
        }
      }

      MCPType.REMINDER_CREATION -> {
        val description = (request.arguments["description"] as? JsonPrimitive)?.content
        val datetime = (request.arguments["datetime"] as? JsonPrimitive)?.content
        val userId = (request.arguments["userId"] as? JsonPrimitive)?.longOrNull ?: -1
        createReminder(description, datetime, userId)
      }

      MCPType.NOTE_SAVING -> {
        val text = (request.arguments["text"] as? JsonPrimitive)?.content
        val userId = (request.arguments["userId"] as? JsonPrimitive)?.longOrNull
        val tags =
          (request.arguments["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val category = (request.arguments["category"] as? JsonPrimitive)?.contentOrNull
        if (text.isNullOrBlank() || userId == null) {
          createErrorResult("Требуются параметры: userId (Long) и text (String)")
        } else {
          // Вместо немедленного сохранения, запрашиваем подтверждение
          val args = buildJsonObject {
            put("text", JsonPrimitive(text))
            put("userId", JsonPrimitive(userId))
            if (tags.isNotEmpty()) put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
            if (category != null) put("category", JsonPrimitive(category))
          }
          queuePendingConfirmation(userId, "save_note", args)
        }
      }

      else -> createErrorResult("Неизвестный инструмент: ${request.type.name}")
    }
    println("[MCPService] executeTool(name=${request.type.name}) -> isError=${result.isError}")
    return result
  }

  /**
   * Публичный метод: поиск в базе знаний и генерация ответа.
   */
  suspend fun queryWithKnowledgeBase(
    userId: Long,
    question: String,
    tags: List<String> = emptyList(),
    category: String? = null
  ): MCPToolResult {
    println("[MCPService] queryWithKnowledgeBase(userId=$userId, question=\"$question\") called")
    // tags/category пока не используются в поиске; зарезервировано для будущего
    val result = executeQueryWithKnowledgeBase(question, userId)
    println("[MCPService] queryWithKnowledgeBase -> returned text content")
    return result
  }

  /**
   * Публичный метод: ответ без поиска в базе знаний (по предоставленному контексту).
   */
  suspend fun queryWithoutKnowledgeBase(
    question: String,
    context: String = ""
  ): MCPToolResult {
    println("[MCPService] queryWithoutKnowledgeBase(question=\"$question\") called")
    val answer = llmService.generateAnswerKnowledgeBase(context, question)
    val result = MCPToolResult(content = listOf(MCPContent(type = "text", text = answer)))
    println("[MCPService] queryWithoutKnowledgeBase -> returned text content")
    return result
  }

  /**
   * Главный метод, который обрабатывает запрос пользователя, позволяя LLM выбрать инструмент.
   */
  suspend fun processUserQuery(query: String, userId: Long): MCPToolResult {
    println("[MCPService] processUserQuery(userId=$userId, query=\"$query\") called")
    val llmDecision = llmService.decideToolOrGenerateAnswer(query, toolRegistry.allTools)

    if (llmDecision == null) {
      println("[MCPService] processUserQuery -> LLM decision is null")
      return createErrorResult("Не удалось получить решение от LLM.")
    }

    // Если LLM решил вызвать инструмент
    if (llmDecision.tool_calls != null) {
      println("[MCPService] processUserQuery -> tool_calls present: ${llmDecision.tool_calls.map { it.function.name }}")
      val toolCall = llmDecision.tool_calls.first()
      val arguments = json.decodeFromString<JsonObject>(toolCall.function.arguments)

      val result = when (toolCall.function.name) {
        "query_with_knowledge_base" -> {
          val question = arguments["question"]?.jsonPrimitive?.content ?: query
          executeQueryWithKnowledgeBase(question, userId)
        }

        "create_reminder" -> {
          val description = arguments["description"]?.jsonPrimitive?.content
          val datetime = arguments["datetime"]?.jsonPrimitive?.content
          createReminder(description, datetime, userId)
        }

        "save_note" -> {
          val text = arguments["text"]?.jsonPrimitive?.content ?: query
          val tags = arguments["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
          val category = arguments["category"]?.jsonPrimitive?.contentOrNull
          val args = buildJsonObject {
            put("text", JsonPrimitive(text))
            put("userId", JsonPrimitive(userId))
            if (tags.isNotEmpty()) put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
            if (category != null) put("category", JsonPrimitive(category))
          }
          queuePendingConfirmation(userId, "save_note", args)
        }

        else -> createErrorResult("LLM предложил неизвестный инструмент: ${toolCall.function.name}")
      }
      println("[MCPService] processUserQuery -> result isError=${result.isError}")
      return result
    }

    // Если LLM просто ответил текстом
    if (llmDecision.content != null) {
      println("[MCPService] processUserQuery -> content reply")
      return MCPToolResult(content = listOf(MCPContent(type = "text", text = llmDecision.content)))
    }

    println("[MCPService] processUserQuery -> unable to process LLM reply")
    return createErrorResult("Не удалось обработать ответ LLM.")
  }

  /**
   * Выполняет поиск в базе знаний.
   */
  private suspend fun executeQueryWithKnowledgeBase(question: String, userId: Long): MCPToolResult {
    println("[MCPService] executeQueryWithKnowledgeBase(userId=$userId, question=\"$question\") called")
    val searchResult = hybridSearchService.search(userId = userId, query = question, limit = 10)
    val context = buildContext(searchResult.notes)
    val answer = if (context.isNotEmpty()) {
      llmService.generateAnswerKnowledgeBase(context, question)
    } else {
      "В базе знаний не найдено релевантной информации по вашему вопросу."
    }
    println("[MCPService] executeQueryWithKnowledgeBase -> contextNotes=${searchResult.notes.size}, totalFound=${searchResult.totalFound}")
    return MCPToolResult(
      content = listOf(
        MCPContent(
          type = "text",
          text = answer,
          metadata = mapOf(
            "sources_count" to JsonPrimitive(searchResult.notes.size),
            "total_found" to JsonPrimitive(searchResult.totalFound)
          )
        )
      )
    )
  }

  /**
   * ЗАГЛУШКА: Создает напоминание.
   */
  private fun createReminder(description: String?, datetime: String?, userId: Long): MCPToolResult {
    println("[MCPService] createReminder(userId=$userId, description=$description, datetime=$datetime) called")
    if (description == null || datetime == null) {
      return createErrorResult("LLM не смог извлечь описание или дату для напоминания.")
    }

    // --- Начало ЗАГЛУШКИ ---
    println("[MCPService] createReminder -> stub execution")
    val parsedTime = try {
      LocalDateTime.parse(datetime)
    } catch (e: Exception) {
      null
    }
    val confirmationText = if (parsedTime != null) {
      "Хорошо, я напомню вам '$description' в $parsedTime."
    } else {
      "Напоминание для '$description создано, но не удалось распознать время."
    }
    return MCPToolResult(
      content = listOf(
        MCPContent(
          type = "text",
          text = confirmationText
        )
      )
    )
  }

  // Поставить действие в очередь на подтверждение и вернуть сообщение подтверждения
  private fun queuePendingConfirmation(userId: Long, toolName: String, args: JsonObject): MCPToolResult {
    val pa = PendingAction(
      id = "${System.currentTimeMillis()}-$userId-$toolName",
      userId = userId,
      toolName = toolName,
      arguments = args
    )
    pendingActionsByUser[userId] = pa
    val previewText = args["text"]?.jsonPrimitive?.content
    val confirmText = if (toolName == "save_note") {
      "Вы хотите сохранить заметку с текстом: \"$previewText\"?"
    } else {
      "Вы уверены, что хотите выполнить инструмент '$toolName'?"
    }
    println("[MCPService] queuePendingConfirmation(userId=$userId, toolName=$toolName) -> pendingId=${pa.id}")
    return MCPToolResult(
      content = listOf(
        MCPContent(type = "text", text = confirmText),
        MCPContent(type = "meta", metadata = mapOf("pending_action_id" to JsonPrimitive(pa.id)))
      )
    )
  }

  // Подтверждение ожидающего действия
  fun confirmPendingAction(userId: Long): MCPToolResult {
    println("[MCPService] confirmPendingAction(userId=$userId) called")
    val pa = pendingActionsByUser.remove(userId)
    if (pa == null) {
      println("[MCPService] confirmPendingAction -> no pending action")
      return createErrorResult("Нет ожидающих действий для подтверждения.")
    }
    val result = when (pa.toolName) {
      "save_note" -> {
        val text = pa.arguments["text"]?.jsonPrimitive?.content ?: return createErrorResult("Не найден текст заметки.")
        val tags = pa.arguments["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val category = pa.arguments["category"]?.jsonPrimitive?.contentOrNull
        saveNote(userId, text, tags, category)
      }

      else -> createErrorResult("Подтверждение для инструмента '${pa.toolName}' не реализовано.")
    }
    println("[MCPService] confirmPendingAction -> isError=${result.isError}")
    return result
  }

  // Отклонение ожидающего действия: показать меню доступных инструментов
  fun rejectPendingAction(userId: Long): MCPToolResult {
    println("[MCPService] rejectPendingAction(userId=$userId) called")
    pendingActionsByUser.remove(userId)
    val toolsList = toolRegistry.allTools.map { it.name }
    val menuText = "Действие отменено. Выберите, что нужно сделать: ${toolsList.joinToString(", ")}."
    val result = MCPToolResult(
      content = listOf(
        MCPContent(type = "text", text = menuText),
        MCPContent(
          type = "meta",
          metadata = mapOf("available_tools" to buildJsonArray { toolsList.forEach { add(JsonPrimitive(it)) } })
        )
      )
    )
    println("[MCPService] rejectPendingAction -> returned tools menu")
    return result
  }

  // Минимальная реализация сохранения заметки
  private fun saveNote(userId: Long, text: String, tags: List<String>, category: String?): MCPToolResult {
    println("[MCPService] saveNote(userId=$userId, textLen=${text.length}, tags=$tags, category=$category) called")
    val noteId = "note-${System.currentTimeMillis()}"
    val note = Note(
      id = noteId,
      userId = userId,
      content = text,
      createdAt = LocalDateTime.now().toString(),
      tags = tags,
      category = category
    )
    val list = notesByUser.getOrPut(userId) { mutableListOf() }
    list.add(note)
    println("[MCPService] saveNote -> saved id=$noteId")
    return MCPToolResult(content = listOf(MCPContent(type = "text", text = "Заметка сохранена (id=$noteId).")))
  }

  private fun buildContext(notes: List<Note>): String {
    return notes.joinToString("\n---\n") { it.content }
  }

  private fun createErrorResult(message: String): MCPToolResult {
    println("[MCPService] ERROR -> $message")
    return MCPToolResult(content = listOf(MCPContent("text", message)), isError = true)
  }
}
