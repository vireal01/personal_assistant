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

// ===== Минимальные модели для OpenAI Responses API =====
@Serializable
private data class ResponsesRequest(
  val model: String,
  val input: List<ResponseInputItem>,
  val tool_choice: String? = null,
  val tools: List<Tool>? = null
)

@Serializable
private data class ResponseInputItem(
  val role: String,
  val content: List<ResponseContentBlock>
)

@Serializable
private data class ResponseContentBlock(
  val type: String = "input_text",
  val text: String
)

@Serializable
private data class ResponsesResponse(
  val id: String? = null,
  val status: String? = null,
  val output: List<ResponsesOutputItem>? = null,
  val error: OpenAIError? = null
)

@Serializable
private data class ResponsesOutputItem(
  val content: List<ResponsesOutputContent>
)

@Serializable
private data class ResponsesOutputContent(
  val type: String,
  val text: String? = null
)

private data class ResponsesResult(
  val id: String?,
  val text: String?
)

class LLMService {
  private val apiKey = System.getenv("OPENAI_API_KEY") ?: ""
  private val useNewApi = (System.getenv("USE_NEW_OPENAI_API") ?: "false").equals("true", ignoreCase = true)
  private val responseModelEnv = System.getenv("OPENAI_RESPONSE_MODEL")
  private val chatModelEnv = System.getenv("OPENAI_CHAT_MODEL")

  private val chatModel: String = when {
    useNewApi && !responseModelEnv.isNullOrBlank() -> responseModelEnv
    !chatModelEnv.isNullOrBlank() -> chatModelEnv
    else -> "gpt-4o-mini"
  }

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    prettyPrint = true
    encodeDefaults = true
  }

  private val client = HttpClient(CIO) {
    install(ContentNegotiation) { json(this@LLMService.json) }
    install(Logging) { logger = Logger.DEFAULT; level = LogLevel.INFO }
    install(HttpTimeout) { requestTimeoutMillis = 60000 }
    defaultRequest {
      header("Authorization", "Bearer $apiKey")
      contentType(ContentType.Application.Json)
    }
  }

  // ===== Helpers =====

  private suspend fun sendResponsesRequest(req: ResponsesRequest): ResponsesResult? {

    val httpResponse = client.post("https://api.openai.com/v1/responses") { setBody(req) }
    return if (httpResponse.status.isSuccess()) {
      val resp = httpResponse.body<ResponsesResponse>()
      val text = parseResponsesText(resp)
      val id = resp.id
      ResponsesResult(id = id, text = text)
    } else {
      val errorBody = httpResponse.bodyAsText()
      println("OpenAI Responses API Error (${httpResponse.status}): $errorBody")
      null
    }
  }

  private fun parseResponsesText(resp: ResponsesResponse): String? {
    // Prefer output_text blocks; fallback to text
    val outputs = resp.output ?: return null
    outputs.forEach { item ->
      val content = item.content
      val outputText = content.firstOrNull { it.type == "output_text" }?.text
      if (outputText != null) return outputText
      val plainText = content.firstOrNull { it.type == "text" }?.text
      if (plainText != null) return plainText
    }
    return null
  }

  private suspend fun sendChatCompletions(req: ChatCompletionRequest): Message? {
    val httpResponse = client.post("https://api.openai.com/v1/chat/completions") { setBody(req) }
    return if (httpResponse.status.isSuccess()) {
      val response = httpResponse.body<ChatCompletionResponse>()
      response.choices?.firstOrNull()?.message
    } else {
      val errorBody = httpResponse.bodyAsText()
      println("OpenAI Chat API Error (${httpResponse.status}): $errorBody")
      null
    }
  }

  // ===== Public API =====

  suspend fun decideToolToUse(userMessage: String, tools: List<Tool>): Message? {
    if (apiKey.isBlank()) {
      return Message("assistant", "API ключ OpenAI не настроен.", null)
    }
    val prompt = buildDecideToolPrompt(userMessage)
    return if (useNewApi) {
      val req = ResponsesRequest(
        model = chatModel,
        input = listOf(ResponseInputItem(role = "user", content = listOf(ResponseContentBlock(text = prompt)))),
        tools = tools,
        tool_choice = "auto",
      )
      val result = sendResponsesRequest(req)
      Message(
        role = "assistant",
        content = result?.text ?: "Получен пустой ответ от LLM. id = ${result?.id}",
        tool_calls = null
      )
    } else {
      val requestBody = ChatCompletionRequest(
        model = chatModel,
        messages = listOf(Message(role = "user", content = prompt, tool_calls = null)),
        tools = tools,
        tool_choice = "auto"
      )
      sendChatCompletions(requestBody)
    }
  }

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

    return if (useNewApi) {
      val req = ResponsesRequest(
        model = chatModel,
        input = listOf(
          ResponseInputItem(
            role = "system",
            content = listOf(ResponseContentBlock(text = "Ты - помощник, который отвечает на вопросы строго на основе предоставленного контекста."))
          ),
          ResponseInputItem(role = "user", content = listOf(ResponseContentBlock(text = prompt)))
        ),
        tool_choice = "none",
        tools = null
      )
      val result = sendResponsesRequest(req)
      result?.text ?: "Получен пустой ответ от LLM."
    } else {
      val requestBody = ChatCompletionRequest(
        model = chatModel,
        messages = listOf(
          Message(
            "system",
            "Ты - помощник, который отвечает на вопросы строго на основе предоставленного контекста.",
            null
          ),
          Message("user", prompt, null)
        ),
        tools = null,
        tool_choice = "none"
      )
      val msg = sendChatCompletions(requestBody)
      msg?.content ?: "Получен пустой ответ от LLM."
    }
  }
}
