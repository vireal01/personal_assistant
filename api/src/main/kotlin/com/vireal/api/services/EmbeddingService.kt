package com.vireal.api.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EmbeddingRequest(
  val model: String = "text-embedding-3-small",
  val input: String,
  val encoding_format: String? = "float"
)

@Serializable
data class EmbeddingBatchRequest(
  val model: String = "text-embedding-3-small",
  val input: List<String>,  // Правильный тип для батча
  val encoding_format: String? = "float"
)

@Serializable
data class EmbeddingResponse(
  val `object`: String,
  val data: List<EmbeddingData>,
  val model: String,
  val usage: EmbeddingUsage
)

@Serializable
data class EmbeddingData(
  val `object`: String,
  val index: Int,
  val embedding: List<Float>
)

@Serializable
data class EmbeddingUsage(
  val prompt_tokens: Int,
  val total_tokens: Int
)

class EmbeddingService {
  companion object {
    // Константы для моделей
    const val MODEL_ADA_002 = "text-embedding-ada-002" // 1536 dimensions
    const val MODEL_3_SMALL = "text-embedding-3-small" // 1536 dimensions
    const val MODEL_3_LARGE = "text-embedding-3-large" // 3072 dimensions

    // Лимиты токенов для разных моделей
    const val MAX_TOKENS_ADA = 8191
    const val MAX_TOKENS_3_SMALL = 8191
    const val MAX_TOKENS_3_LARGE = 8191
  }

  private val apiKey = System.getenv("OPENAI_API_KEY") ?: ""
  private val apiUrl = "https://api.openai.com/v1/embeddings"
  private val embeddingModel = System.getenv("OPENAI_EMBEDDING_MODEL") ?: MODEL_3_SMALL

  private val client = HttpClient(CIO) {
    install(ContentNegotiation) {
      json(Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
      })
    }
    install(Logging) {
      level = LogLevel.INFO
    }
    defaultRequest {
      header("Authorization", "Bearer $apiKey")
      contentType(ContentType.Application.Json)
    }
  }

  suspend fun createEmbedding(
    text: String,
    model: String = embeddingModel,
    encodingFormat: String = "float"
  ): List<Float>? {
    if (!isApiKeyValid()) {
      println("Warning: OpenAI API key not configured, skipping embedding creation")
      return null
    }

    return try {
      val truncatedText = truncateText(text, model)

      val response = client.post(apiUrl) {
        setBody(
          EmbeddingRequest(
            model = model,
            input = truncatedText,
            encoding_format = encodingFormat
          )
        )
      }

      val embeddingResponse = response.body<EmbeddingResponse>()
      val embedding = embeddingResponse.data.firstOrNull()?.embedding

      println(
        """
                Created embedding:
                - Model: ${embeddingResponse.model}
                - Text preview: '${truncatedText.take(50)}...'
                - Dimensions: ${embedding?.size}
                - Tokens used: ${embeddingResponse.usage.total_tokens}
            """.trimIndent()
      )

      embedding

    } catch (e: Exception) {
      println("Error creating embedding: ${e.message}")
      e.printStackTrace()
      null
    }
  }

  /**
   * Создает embeddings для множества текстов за один запрос
   * OpenAI API поддерживает батчинг до 2048 элементов
   */
  suspend fun createEmbeddings(
    texts: List<String>,
    model: String = embeddingModel,
    encodingFormat: String = "float"
  ): List<List<Float>>? {
    if (!isApiKeyValid()) {
      println("Warning: OpenAI API key not configured, skipping embeddings creation")
      return null
    }

    if (texts.isEmpty()) {
      return emptyList()
    }

    // OpenAI поддерживает до 2048 inputs в одном запросе
    val batchLimit = 2048
    if (texts.size > batchLimit) {
      println("Warning: Too many texts (${texts.size}), processing only first $batchLimit")
    }

    return try {
      // Обрезаем тексты и ограничиваем количество
      val processedTexts = texts
        .take(batchLimit)
        .map { truncateText(it, model) }

      val response = client.post(apiUrl) {
        setBody(
          EmbeddingBatchRequest(
            model = model,
            input = processedTexts,  // Используем правильный тип запроса
            encoding_format = encodingFormat
          )
        )
      }

      val embeddingResponse = response.body<EmbeddingResponse>()
      val embeddings = embeddingResponse.data
        .sortedBy { it.index }
        .map { it.embedding }

      println(
        """
                Created batch embeddings:
                - Model: ${embeddingResponse.model}
                - Batch size: ${embeddings.size}
                - Dimensions per embedding: ${embeddings.firstOrNull()?.size}
                - Total tokens used: ${embeddingResponse.usage.total_tokens}
            """.trimIndent()
      )

      embeddings

    } catch (e: Exception) {
      println("Error creating batch embeddings: ${e.message}")
      e.printStackTrace()
      null
    }
  }

  /**
   * Создает embeddings для больших батчей, разбивая на части если нужно
   */
  suspend fun createEmbeddingsLargeBatch(
    texts: List<String>,
    batchSize: Int = 100,
    model: String = embeddingModel
  ): List<List<Float>> {
    if (!isApiKeyValid()) {
      println("Warning: OpenAI API key not configured")
      return emptyList()
    }

    val allEmbeddings = mutableListOf<List<Float>>()

    texts.chunked(batchSize).forEach { batch ->
      val batchEmbeddings = createEmbeddings(batch, model)
      if (batchEmbeddings != null) {
        allEmbeddings.addAll(batchEmbeddings)
      } else {
        // Если батч не удался, пробуем по одному
        println("Batch failed, processing individually")
        batch.forEach { text ->
          val embedding = createEmbedding(text, model)
          if (embedding != null) {
            allEmbeddings.add(embedding)
          } else {
            // Добавляем пустой embedding чтобы сохранить индексы
            allEmbeddings.add(emptyList())
          }
        }
      }

      // Небольшая задержка между батчами для избежания rate limiting
      if (texts.size > batchSize) {
        delay(100)
      }
    }

    return allEmbeddings
  }

  private fun isApiKeyValid(): Boolean {
    return apiKey.isNotEmpty() &&
      apiKey != "test_key" &&
      !apiKey.startsWith("your_") &&
      apiKey.startsWith("sk-")
  }

  private fun truncateText(text: String, model: String): String {
    val maxChars = when (model) {
      MODEL_ADA_002 -> 30000 // примерно ~8000 токенов
      MODEL_3_SMALL -> 30000
      MODEL_3_LARGE -> 30000
      else -> 8000 // безопасное значение по умолчанию
    }

    return if (text.length > maxChars) {
      println("Warning: Text truncated from ${text.length} to $maxChars chars")
      text.take(maxChars)
    } else {
      text
    }
  }

  fun close() {
    client.close()
  }
}
