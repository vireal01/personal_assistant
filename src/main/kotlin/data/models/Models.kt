package com.vireal.data.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Note(
    val id: String,
    val userId: Long,
    val content: String,
    val createdAt: String,
    val tags: List<String> = emptyList(),
    val category: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val embedding: List<Float>? = null // Не возвращаем клиенту для экономии трафика
)

@Serializable
data class CreateNoteRequest(
    val userId: Long,
    val content: String,
    val tags: List<String> = emptyList(),
    val category: String? = null,
    val metadata: JsonObject? = null,
    val generateEmbedding: Boolean = true // Флаг для автоматической генерации embedding
)

@Serializable
data class UpdateNoteRequest(
    val content: String? = null,
    val tags: List<String>? = null,
    val category: String? = null,
    val metadata: JsonObject? = null,
    val regenerateEmbedding: Boolean = false // Флаг для перегенерации embedding при обновлении
)

@Serializable
data class CreateNoteResponse(
    val success: Boolean,
    val noteId: String? = null,
    val message: String
)

@Serializable
data class QueryRequest(
    val userId: Long,
    val question: String,
    val categories: List<String>? = null,
    val tags: List<String>? = null,
    val useSemanticSearch: Boolean = true, // Использовать векторный поиск
    val topK: Int = 5 // Количество наиболее релевантных результатов
)

@Serializable
data class QueryResponse(
    val answer: String,
    val sources: List<String> = emptyList(),
    val relevanceScores: List<Float>? = null // Оценки релевантности для семантического поиска
)

@Serializable
data class SearchResult(
    val notes: List<Note>,
    val totalFound: Int,
    val facets: SearchFacets? = null
)

@Serializable
data class SearchFacets(
    val categories: Map<String, Int> = emptyMap(),
    val tags: Map<String, Int> = emptyMap()
)