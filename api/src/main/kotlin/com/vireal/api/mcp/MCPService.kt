package com.vireal.api.mcp

import com.vireal.api.services.EmbeddingService
import com.vireal.api.services.HybridSearchService
import com.vireal.api.services.LLMService
import com.vireal.shared.models.MCPContent
import com.vireal.shared.models.MCPToolResult
import com.vireal.shared.models.Note
import kotlinx.serialization.json.*
import java.time.LocalDateTime

/**
 * MCP сервис, который использует LLM для выбора и вызова инструментов.
 */
class MCPService(
    private val llmService: LLMService = LLMService(),
    private val hybridSearchService: HybridSearchService = HybridSearchService(),
    private val embeddingService: EmbeddingService = EmbeddingService()
    // private val reminderService: ReminderService = ReminderService() // Будет добавлено в будущем
) {
    private val toolRegistry = ToolRegistry(llmService)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Главный метод, который обрабатывает запрос пользователя, позволяя LLM выбрать инструмент.
     */
    suspend fun processUserQuery(query: String, userId: Long): MCPToolResult {
        val llmDecision = llmService.decideToolOrGenerateAnswer(query, toolRegistry.allTools)

        if (llmDecision == null) {
            return createErrorResult("Не удалось получить решение от LLM.")
        }

        // Если LLM решил вызвать инструмент
        if (llmDecision.tool_calls != null) {
            val toolCall = llmDecision.tool_calls.first()
            val arguments = json.decodeFromString<JsonObject>(toolCall.function.arguments)

            return when (toolCall.function.name) {
                "query_with_knowledge_base" -> {
                    val question = arguments["question"]?.jsonPrimitive?.content ?: query
                    executeQueryWithKnowledgeBase(question, userId)
                }
                "create_reminder" -> {
                    val description = arguments["description"]?.jsonPrimitive?.content
                    val datetime = arguments["datetime"]?.jsonPrimitive?.content
                    createReminder(description, datetime, userId)
                }
                else -> createErrorResult("LLM предложил неизвестный инструмент: ${toolCall.function.name}")
            }
        }

        // Если LLM просто ответил текстом
        if (llmDecision.content != null) {
            return MCPToolResult(content = listOf(MCPContent(type = "text", text = llmDecision.content)))
        }

        return createErrorResult("Не удалось обработать ответ LLM.")
    }

    /**
     * Выполняет поиск в базе знаний.
     */
    private suspend fun executeQueryWithKnowledgeBase(question: String, userId: Long): MCPToolResult {
        val searchResult = hybridSearchService.search(userId = userId, query = question, limit = 10)
        val context = buildContext(searchResult.notes)

        // Здесь можно сделать второй вызов LLM для генерации ответа на основе найденного контекста,
        // но для простоты пока вернем сырой результат.
        val answer = if (context.isNotEmpty()) {
            llmService.generateAnswerKnowledgeBase(context, question)
        } else {
            "В базе знаний не найдено релевантной информации по вашему вопросу."
        }

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
        if (description == null || datetime == null) {
            return createErrorResult("LLM не смог извлечь описание или дату для напоминания.")
        }

        // --- Начало ЗАГЛУШКИ ---
        // В будущем здесь будет реальная логика:
        // 1. Парсинг `datetime` в LocalDateTime.
        // 2. Сохранение в `reminderService`.
        // 3. Возврат подтверждения.
        println("ЗАГЛУШКА: Создание напоминания для userId=$userId. Описание: '$description', Время: '$datetime'")
        val parsedTime = try { LocalDateTime.parse(datetime) } catch (e: Exception) { null }
        val confirmationText = if (parsedTime != null) {
            "Хорошо, я напомню вам '$description' в $parsedTime."
        } else {
            "Напоминание для '$description' создано, но не удалось распознать время."
        }
        // --- Конец ЗАГЛУШКИ ---

        return MCPToolResult(
            content = listOf(
                MCPContent(
                    type = "text",
                    text = confirmationText
                )
            )
        )
    }

    private fun buildContext(notes: List<Note>): String {
        return notes.joinToString("\n---\n") { it.content }
    }

    private fun createErrorResult(message: String): MCPToolResult {
        return MCPToolResult(content = listOf(MCPContent("text", message)), isError = true)
    }
}


