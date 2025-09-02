package com.vireal.data.models

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val id: String,
    val userId: Long,
    val content: String,
    val createdAt: String
)

@Serializable
data class CreateNoteRequest(
    val userId: Long,
    val content: String
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
    val question: String
)

@Serializable
data class QueryResponse(
    val answer: String,
    val sources: List<String> = emptyList()
)

@Serializable
data class SearchResult(
    val notes: List<Note>,
    val totalFound: Int
)