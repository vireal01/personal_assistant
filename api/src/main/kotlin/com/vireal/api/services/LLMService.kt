package com.vireal.api.services

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ===== Классы для /v1/responses (новый API) =====
@Serializable
data class ResponsesRequest(
  val model: String,  // Обязательное поле!
  val input: String
)

@Serializable
data class ResponsesAPIResponse(
  val id: String,
  val `object`: String? = null,
  val created_at: Long? = null,
  val status: String? = null,
  val model: String? = null,
  val output: List<OutputMessage>? = null,
  val usage: Usage? = null,
  val error: OpenAIError? = null
)

@Serializable
data class OutputMessage(
  val id: String? = null,
  val type: String? = null,
  val status: String? = null,
  val content: List<OutputContent>? = null,
  val role: String? = null
)

@Serializable
data class OutputContent(
  val type: String? = null,
  val text: String? = null,
  val annotations: List<String>? = null
)

// ===== Классы для /v1/chat/completions (классический API) =====
@Serializable
data class ChatCompletionRequest(
  val model: String = "gpt-3.5-turbo",
  val messages: List<Message>,
  val temperature: Double = 0.7,
  val max_tokens: Int = 500
)

@Serializable
data class Message(
  val role: String,
  val content: String
)

@Serializable
data class ChatCompletionResponse(
  val id: String? = null,
  val `object`: String? = null,
  val created: Long? = null,
  val model: String? = null,
  val choices: List<Choice>? = null,
  val usage: Usage? = null,
  val error: OpenAIError? = null
)

@Serializable
data class Choice(
  val index: Int? = null,
  val message: Message,
  val finish_reason: String? = null
)

@Serializable
data class Usage(
  val input_tokens: Int? = null,
  val output_tokens: Int? = null,
  val total_tokens: Int? = null,
  val prompt_tokens: Int? = null,
  val completion_tokens: Int? = null
)

@Serializable
data class OpenAIError(
  val message: String,
  val type: String? = null,
  val param: String? = null,
  val code: String? = null
)

class LLMService {
  private val apiKey = System.getenv("OPENAI_API_KEY") ?: ""
  private val useNewAPI = System.getenv("USE_NEW_OPENAI_API")?.toBoolean() ?: false

  // Модели для разных API
  private val responseModel = System.getenv("OPENAI_RESPONSE_MODEL") ?: "gpt-4.1"
  private val chatModel = System.getenv("OPENAI_CHAT_MODEL") ?: "gpt-3.5-turbo"

  private val apiUrl = if (useNewAPI) {
    "https://api.openai.com/v1/responses"
  } else {
    "https://api.openai.com/v1/chat/completions"
  }

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    prettyPrint = true
  }

  private val client = HttpClient(CIO) {
    install(ContentNegotiation) {
      json(this@LLMService.json)
    }
    install(Logging) {
      logger = Logger.DEFAULT
      level = LogLevel.INFO
    }
    install(HttpTimeout) {
      requestTimeoutMillis = 30000
      connectTimeoutMillis = 10000
    }
    defaultRequest {
      header("Authorization", "Bearer $apiKey")
      contentType(ContentType.Application.Json)
    }
  }

  suspend fun generateAnswer(context: String, question: String): String {
    if (apiKey.isEmpty() || apiKey == "test_key") {
      return "LLM API ключ не настроен. Установите переменную окружения OPENAI_API_KEY"
    }

    println("=== OpenAI API Call ===")
    println("Using API: ${if (useNewAPI) "New Responses API" else "Classic Chat Completions API"}")
    println("API URL: $apiUrl")
    println("Model: ${if (useNewAPI) responseModel else chatModel}")

    val prompt = buildPrompt(context, question)

    return if (useNewAPI) {
      callNewResponsesAPI(prompt)
    } else {
      callClassicChatAPI(prompt)
    }
  }

  private suspend fun callNewResponsesAPI(prompt: String): String {
    val requestBody = ResponsesRequest(
      model = responseModel,  // Используем правильную модель
      input = prompt
    )

    return try {
      println("Request to Responses API: ${json.encodeToString(ResponsesRequest.serializer(), requestBody)}")

      val httpResponse = client.post(apiUrl) {
        setBody(requestBody)
      }

      val responseBody = httpResponse.bodyAsText()
      println("Response Status: ${httpResponse.status}")

      when (httpResponse.status) {
        HttpStatusCode.OK -> {
          println("Response body: $responseBody")
          val response = json.decodeFromString<ResponsesAPIResponse>(responseBody)

          response.error?.let { error ->
            return "OpenAI API error: ${error.message}"
          }

          // Извлекаем текст из нового формата
          val text = response.output
            ?.firstOrNull()
            ?.content
            ?.firstOrNull { it.type == "output_text" }
            ?.text

          text ?: "Получен пустой ответ от LLM"
        }

        HttpStatusCode.BadRequest -> {
          println("Bad Request. Response: $responseBody")
          "Ошибка запроса (400): $responseBody"
        }

        HttpStatusCode.Unauthorized -> {
          "Ошибка авторизации (401). Проверьте API ключ"
        }

        else -> {
          println("Unexpected status. Response: $responseBody")
          "Ошибка API (${httpResponse.status}): ${responseBody.take(200)}"
        }
      }
    } catch (e: Exception) {
      println("Exception: ${e.message}")
      e.printStackTrace()
      "Ошибка при обращении к Responses API: ${e.message}"
    }
  }

  private suspend fun callClassicChatAPI(prompt: String): String {
    val requestBody = ChatCompletionRequest(
      model = chatModel,
      messages = listOf(
        Message(
          role = "system",
          content = "Ты - помощник, который отвечает на вопросы строго на основе предоставленного контекста."
        ),
        Message(
          role = "user",
          content = prompt
        )
      )
    )

    return try {
      println(
        "Request to Chat Completions API: ${
          json.encodeToString(
            ChatCompletionRequest.serializer(),
            requestBody
          )
        }"
      )

      val httpResponse = client.post(apiUrl) {
        setBody(requestBody)
      }

      val responseBody = httpResponse.bodyAsText()
      println("Response Status: ${httpResponse.status}")

      when (httpResponse.status) {
        HttpStatusCode.OK -> {
          val response = json.decodeFromString<ChatCompletionResponse>(responseBody)

          response.error?.let { error ->
            return "OpenAI API error: ${error.message}"
          }

          response.choices?.firstOrNull()?.message?.content
            ?: "Получен пустой ответ от LLM"
        }

        HttpStatusCode.Unauthorized -> "Ошибка авторизации. Проверьте API ключ"
        else -> "Ошибка API (${httpResponse.status}): ${responseBody.take(200)}"
      }
    } catch (e: Exception) {
      println("Exception: ${e.message}")
      "Ошибка при обращении к Chat API: ${e.message}"
    }
  }

  private fun buildPrompt(context: String, question: String): String {
    return if (context.isNotEmpty()) {
      """
            |Контекст из базы знаний:
            |$context
            |
            |Вопрос пользователя: $question
            |
            |Ответь на вопрос, используя ТОЛЬКО информацию из предоставленного контекста.
            """.trimMargin()
    } else {
      """
            |В базе знаний не найдено информации.
            |Вопрос: $question
            |Объясни, что информации пока нет в базе.
            """.trimMargin()
    }
  }

  fun close() {
    client.close()
  }
}
