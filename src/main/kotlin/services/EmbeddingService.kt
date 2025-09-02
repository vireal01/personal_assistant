package com.vireal.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EmbeddingRequest(
    val model: String = "text-embedding-3-small",
    val input: String,
    val encoding_format: String? = "float" // Добавлено опциональное поле
)

@Serializable
data class EmbeddingResponse(
    val `object`: String, // Сделано обязательным, так как всегда возвращается "list"
    val data: List<EmbeddingData>,
    val model: String,
    val usage: EmbeddingUsage
)

@Serializable
data class EmbeddingData(
    val `object`: String, // Сделано обязательным, всегда "embedding"
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

    // Используем правильную переменную окружения для embedding модели
    private val embeddingModel = System.getenv("OPENAI_EMBEDDING_MODEL") ?: MODEL_3_SMALL

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true // Для включения значений по умолчанию
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
        if (apiKey.isEmpty() || apiKey == "test_key") {
            println("Warning: OpenAI API key not configured, skipping embedding creation")
            return null
        }

        return try {
            // Ограничиваем длину текста в зависимости от модели
            val maxChars = when (model) {
                MODEL_ADA_002 -> 30000 // примерно ~8000 токенов
                MODEL_3_SMALL -> 30000
                MODEL_3_LARGE -> 30000
                else -> 8000 // безопасное значение по умолчанию
            }

            val truncatedText = text.take(maxChars)

            val response = client.post(apiUrl) {
                setBody(EmbeddingRequest(
                    model = model,
                    input = truncatedText,
                    encoding_format = encodingFormat
                ))
            }

            val embeddingResponse = response.body<EmbeddingResponse>()
            val embedding = embeddingResponse.data.firstOrNull()?.embedding

            println("""
                Created embedding:
                - Model: ${embeddingResponse.model}
                - Text preview: '${truncatedText.take(50)}...'
                - Dimensions: ${embedding?.size}
                - Tokens used: ${embeddingResponse.usage.total_tokens}
            """.trimIndent())

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
        if (apiKey.isEmpty() || apiKey == "test_key") {
            println("Warning: OpenAI API key not configured, skipping embeddings creation")
            return null
        }

        if (texts.isEmpty()) {
            return emptyList()
        }

        // OpenAI поддерживает до 2048 inputs в одном запросе
        if (texts.size > 2048) {
            println("Warning: Too many texts (${texts.size}), processing only first 2048")
        }

        return try {
            val processedTexts = texts.take(2048).map { it.take(8000) }

            val response = client.post(apiUrl) {
                setBody(mapOf(
                    "model" to model,
                    "input" to processedTexts,
                    "encoding_format" to encodingFormat
                ))
            }

            val embeddingResponse = response.body<EmbeddingResponse>()
            val embeddings = embeddingResponse.data
                .sortedBy { it.index }
                .map { it.embedding }

            println("""
                Created batch embeddings:
                - Model: ${embeddingResponse.model}
                - Batch size: ${embeddings.size}
                - Dimensions per embedding: ${embeddings.firstOrNull()?.size}
                - Total tokens used: ${embeddingResponse.usage.total_tokens}
            """.trimIndent())

            embeddings

        } catch (e: Exception) {
            println("Error creating batch embeddings: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    fun close() {
        client.close()
    }
}