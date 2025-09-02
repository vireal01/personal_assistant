package com.vireal.services

import com.vireal.data.models.*
import com.vireal.data.repository.NotesRepository

class NotesService(
    private val repository: NotesRepository = NotesRepository()
) {

    suspend fun addNote(userId: Long, content: String): CreateNoteResponse {
        return try {
            val noteId = repository.createNote(userId, content)
            CreateNoteResponse(
                success = true,
                noteId = noteId.toString(),
                message = "Запись успешно добавлена"
            )
        } catch (e: Exception) {
            CreateNoteResponse(
                success = false,
                message = "Ошибка при добавлении записи: ${e.message}"
            )
        }
    }

    suspend fun searchNotes(userId: Long, query: String): SearchResult {
        val notes = repository.searchNotes(userId, query)
        return SearchResult(
            notes = notes,
            totalFound = notes.size
        )
    }

    suspend fun getUserNotes(userId: Long): List<Note> {
        return repository.getUserNotes(userId)
    }
}