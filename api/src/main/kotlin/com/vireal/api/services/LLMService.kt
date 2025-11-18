package com.vireal.api.services

import com.vireal.shared.models.Tool
import com.vireal.shared.models.ToolCall
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ===== Модели для Chat Completions API с поддержкой Tool Calling =====

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val tool_choice: String? = "auto"
)

@Serializable
data class Message(
    val role: String,
    val content: String?,
    val tool_calls: List<ToolCall>? = null
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice>? = null,
    val error: OpenAIError? = null
)

@Serializable
data class Choice(
    val message: Message,
    val finish_reason: String? = null
)

@Serializable
data class OpenAIError(
    val message: String,
    val type: String? = null,
    val code: String? = null
)

class LLMService {
    private val apiKey = System.getenv("OPENAI_API_KEY") ?: ""
    private val chatModel = System.getenv("OPENAI_CHAT_MODEL") ?: "gpt-4-turbo-preview"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        prettyPrint = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(this@LLMService.json) }
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
        install(HttpTimeout) { requestTimeoutMillis = 60000 }
        defaultRequest {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
        }
    }

    /**
     * Определяет, какой инструмент использовать, или генерирует простой ответ.
     */
    suspend fun decideToolOrGenerateAnswer(userQuery: String, tools: List<Tool>): Message? {
        if (apiKey.isBlank()) {
            return Message("assistant", "API ключ OpenAI не настроен.", null)
        }

        val requestBody = ChatCompletionRequest(
            model = chatModel,
            messages = listOf(Message(role = "user", content = userQuery, tool_calls = null)),
            tools = tools,
            tool_choice = "auto"
        )

        return try {
            val httpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                setBody(requestBody)
            }

            if (httpResponse.status.isSuccess()) {
                val response = httpResponse.body<ChatCompletionResponse>()
                response.choices?.firstOrNull()?.message
            } else {
                val errorBody = httpResponse.bodyAsText()
                println("OpenAI API Error: $errorBody")
                Message("assistant", "Ошибка при обращении к OpenAI: ${httpResponse.status}", null)
            }
        } catch (e: Exception) {
            println("Exception during OpenAI call: ${e.message}")
            Message("assistant", "Исключение при вызове LLM: ${e.message}", null)
        }
    }

    /**
     * Генерирует ответ на основе предоставленного контекста.
     */
    suspend fun generateAnswerKnowledgeBase(context: String, question: String): String {
        if (apiKey.isBlank()) {
            return "API ключ OpenAI не настроен."
        }
        val prompt = """
            Контекст:
            $context
            ---
            Вопрос: $question
            Ответь на вопрос, используя только предоставленный контекст.
        """.trimIndent()

        val requestBody = ChatCompletionRequest(
            model = chatModel,
            messages = listOf(
                Message("system", "Ты - помощник, который отвечает на вопросы строго на основе предоставленного контекста.", null),
                Message("user", prompt, null)
            ),
            tools = null,
            tool_choice = "none" // Явно запрещаем использовать инструменты
        )

        return try {
            val httpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                setBody(requestBody)
            }
            if (httpResponse.status.isSuccess()) {
                val response = httpResponse.body<ChatCompletionResponse>()
                response.choices?.firstOrNull()?.message?.content ?: "Получен пустой ответ от LLM."
            } else {
                "Ошибка API: ${httpResponse.status}"
            }
        } catch (e: Exception) {
            "Исключение при генерации ответа: ${e.message}"
        }
    }

    fun close() {
        client.close()
    }
}
