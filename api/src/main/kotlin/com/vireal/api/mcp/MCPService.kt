package com.vireal.api.mcp

import com.vireal.api.services.HybridSearchService
import com.vireal.api.services.LLMService
import com.vireal.api.services.NotesService
import com.vireal.shared.models.DecideMCPToolResult
import com.vireal.shared.models.MCPContent
import com.vireal.shared.models.MCPDecideToolRequest
import com.vireal.shared.models.MCPTool
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
  private val notesService: NotesService = NotesService(),
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

  suspend fun saveNote(userId: Long, text: String, tags: List<String>, category: String?): MCPToolResult {
    println("[MCPService] saveNote(userId=$userId, textLen=${text.length}, tags=$tags, category=$category) called")
    val response = notesService.addNote(userId = userId, content = text)
    val msg = if (response.success) {
      val info = response.message
      "$info (id=${response.noteId})"
    } else {
      response.message
    }
    val isErr = !response.success
    println("[MCPService] saveNote -> ${if (isErr) "error" else "ok"}: $msg")
    return MCPToolResult(content = listOf(MCPContent(type = "text", text = msg)), isError = isErr)
  }

  /**
   * Поиск заметок пользователя через NotesService и возврат результата в MCP формате.
   */
  suspend fun searchNotes(userId: Long, query: String): MCPToolResult {
    println("[MCPService] searchNotes(userId=$userId, query=\"$query\") called")
    return try {
      val res = notesService.searchNotes(userId, query)
      val context = buildContext(res.notes)
      val meta = mapOf(
        "sources_count" to JsonPrimitive(res.notes.size),
        "total_found" to JsonPrimitive(res.totalFound)
      )
      MCPToolResult(
        content = listOf(
          MCPContent(type = "text", text = context.ifEmpty { "Ничего не найдено по запросу." }, metadata = meta)
        )
      )
    } catch (e: Exception) {
      println("[MCPService] searchNotes ERROR -> ${e.message}")
      MCPToolResult(content = listOf(MCPContent(type = "text", text = "Ошибка поиска: ${e.message}")), isError = true)
    }
  }

  private fun buildContext(notes: List<Note>): String {
    return notes.joinToString("\n---\n") { it.content }
  }

  private fun createErrorResult(message: String): MCPToolResult {
    println("[MCPService] ERROR -> $message")
    return MCPToolResult(content = listOf(MCPContent("text", message)), isError = true)
  }
}
