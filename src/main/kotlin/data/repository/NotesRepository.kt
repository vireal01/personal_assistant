package com.vireal.data.repository

import com.vireal.data.database.DatabaseFactory.dbQuery
import com.vireal.data.database.Notes
import com.vireal.data.models.Note
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import java.util.UUID

class NotesRepository {

    suspend fun createNote(userId: Long, content: String): UUID = dbQuery {
        Notes.insert {
            it[Notes.userId] = userId
            it[Notes.content] = content
        }[Notes.id]
    }

    suspend fun getNoteById(id: UUID): Note? = dbQuery {
        Notes.select { Notes.id eq id }
            .map(::resultRowToNote)
            .singleOrNull()
    }

    // Простой поиск по подстроке (для начала)
    suspend fun searchNotes(userId: Long, query: String, limit: Int = 5): List<Note> = dbQuery {
        Notes.select {
            (Notes.userId eq userId) and
                    (Notes.content like "%$query%")
        }
            .orderBy(Notes.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::resultRowToNote)
    }

    // Полнотекстовый поиск через raw SQL
    suspend fun searchNotesFullText(userId: Long, query: String, limit: Int = 5): List<Note> = dbQuery {
        // Разбиваем запрос на отдельные слова для поиска
        val searchTerms = query.split(" ")
            .filter { it.isNotBlank() && it.length > 1 }

        if (searchTerms.isEmpty()) {
            return@dbQuery emptyList()
        }

        // Используем простой поиск по подстроке для каждого термина
        // Ищем записи, которые содержат хотя бы одно из ключевых слов
        var searchCondition: Op<Boolean> = Notes.content like "%${searchTerms.first()}%"
        
        for (term in searchTerms.drop(1)) {
            searchCondition = searchCondition or (Notes.content like "%$term%")
        }

        Notes.select {
            (Notes.userId eq userId) and searchCondition
        }
            .orderBy(Notes.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::resultRowToNote)
    }

    suspend fun getUserNotes(userId: Long, limit: Int = 100): List<Note> = dbQuery {
        Notes.select { Notes.userId eq userId }
            .orderBy(Notes.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::resultRowToNote)
    }

    private fun resultRowToNote(row: ResultRow) = Note(
        id = row[Notes.id].toString(),
        userId = row[Notes.userId],
        content = row[Notes.content],
        createdAt = row[Notes.createdAt].toString()
    )
}