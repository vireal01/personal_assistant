package com.vireal.data.repository

import com.vireal.data.database.DatabaseFactory.dbQuery
import com.vireal.data.database.Notes
import com.vireal.data.database.DataHelper
import com.vireal.data.models.Note
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import java.util.UUID

class NotesRepository {

    suspend fun createNote(userId: Long, content: String): UUID = dbQuery {
        Notes.insert {
            it[Notes.userId] = userId
            it[Notes.content] = content
        }[Notes.id]
    }

    // Создание заметки с полными метаданными
    suspend fun createNoteWithMetadata(
        userId: Long,
        content: String,
        tags: List<String>? = null,
        category: String? = null,
        metadata: Map<String, Any>? = null
    ): UUID = dbQuery {
        Notes.insert {
            it[Notes.userId] = userId
            it[Notes.content] = content
            it[Notes.tags] = DataHelper.formatTags(tags ?: emptyList())
            it[Notes.category] = category
            it[Notes.metadata] = DataHelper.formatMetadata(metadata ?: emptyMap())
        }[Notes.id]
    }

    suspend fun updateNote(
        noteId: UUID,
        content: String? = null,
        tags: List<String>? = null,
        category: String? = null,
        metadata: Map<String, Any>? = null
    ): Boolean = dbQuery {
        Notes.update({ Notes.id eq noteId }) {
            content?.let { c -> it[Notes.content] = c }
            tags?.let { t -> it[Notes.tags] = DataHelper.formatTags(t) }
            category?.let { c -> it[Notes.category] = c }
            metadata?.let { m -> it[Notes.metadata] = DataHelper.formatMetadata(m) }
        } > 0
    }

    suspend fun getNoteById(id: UUID): Note? = dbQuery {
        Notes.select { Notes.id eq id }
            .map(::resultRowToNote)
            .singleOrNull()
    }

    // Поиск заметок с фильтрами
    suspend fun searchNotes(
        userId: Long,
        query: String,
        limit: Int = 5,
        tags: List<String>? = null,
        category: String? = null
    ): List<Note> = dbQuery {
        var condition: Op<Boolean> = (Notes.userId eq userId) and (Notes.content like "%$query%")

        // Фильтр по категории
        category?.let {
            condition = condition and (Notes.category eq it)
        }

        // Фильтр по тегам - поиск в JSON строке
        if (!tags.isNullOrEmpty()) {
            tags.forEach { tag ->
                condition = condition and (Notes.tags like "%\"$tag\"%")
            }
        }

        Notes.select(condition)
            .orderBy(Notes.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::resultRowToNote)
    }

    // Полнотекстовый поиск
    suspend fun searchNotesFullText(
        userId: Long,
        query: String,
        limit: Int = 5,
        tags: List<String>? = null,
        category: String? = null
    ): List<Note> = dbQuery {
        val searchTerms = query.split(" ")
            .filter { it.isNotBlank() && it.length > 1 }

        if (searchTerms.isEmpty()) {
            return@dbQuery emptyList()
        }

        // Базовое условие поиска
        var searchCondition: Op<Boolean> = Notes.content like "%${searchTerms.first()}%"

        for (term in searchTerms.drop(1)) {
            searchCondition = searchCondition or (Notes.content like "%$term%")
        }

        var finalCondition = (Notes.userId eq userId) and searchCondition

        // Добавляем фильтры
        category?.let {
            finalCondition = finalCondition and (Notes.category eq it)
        }

        if (!tags.isNullOrEmpty()) {
            tags.forEach { tag ->
                finalCondition = finalCondition and (Notes.tags like "%\"$tag\"%")
            }
        }

        Notes.select(finalCondition)
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

    // Получение заметок по категории
    suspend fun getNotesByCategory(userId: Long, category: String, limit: Int = 50): List<Note> = dbQuery {
        Notes.select {
            (Notes.userId eq userId) and (Notes.category eq category)
        }
            .orderBy(Notes.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::resultRowToNote)
    }

    // Получение заметок по тегам
    suspend fun getNotesByTags(userId: Long, tags: List<String>, limit: Int = 50): List<Note> = dbQuery {
        var condition: Op<Boolean> = Notes.userId eq userId

        // Поиск каждого тега в JSON массиве
        tags.forEach { tag ->
            condition = condition and (Notes.tags like "%\"$tag\"%")
        }

        Notes.select(condition)
            .orderBy(Notes.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::resultRowToNote)
    }

    // Получение всех уникальных тегов пользователя
    suspend fun getUserTags(userId: Long): Set<String> = dbQuery {
        val allTags = mutableSetOf<String>()

        Notes.select { Notes.userId eq userId }
            .forEach { row ->
                val tags = DataHelper.parseTags(row[Notes.tags])
                allTags.addAll(tags)
            }

        allTags
    }

    // Получение статистики по категориям
    suspend fun getCategoryStats(userId: Long): Map<String, Int> = dbQuery {
        val stats = mutableMapOf<String, Int>()

        Notes.select {
            (Notes.userId eq userId) and (Notes.category.isNotNull())
        }.forEach { row ->
            val category = row[Notes.category]
            if (category != null) {
                stats[category] = stats.getOrDefault(category, 0) + 1
            }
        }

        stats
    }

    // Удаление заметки
    suspend fun deleteNote(noteId: UUID): Boolean = dbQuery {
        Notes.deleteWhere { id eq noteId } > 0
    }

    // Проверка существования заметки
    suspend fun noteExists(noteId: UUID): Boolean = dbQuery {
        Notes.select { Notes.id eq noteId }.count() > 0
    }

    // Получение количества заметок пользователя
    suspend fun getUserNotesCount(userId: Long): Long = dbQuery {
        Notes.select { Notes.userId eq userId }.count()
    }

    private fun resultRowToNote(row: ResultRow) = Note(
        id = row[Notes.id].toString(),
        userId = row[Notes.userId],
        content = row[Notes.content],
        createdAt = row[Notes.createdAt].toString(),
        tags = DataHelper.parseTags(row[Notes.tags]),
        category = row[Notes.category],
        metadata = DataHelper.mapToJsonObject(
            DataHelper.parseMetadata(row[Notes.metadata])
        ),
        embedding = null // Не возвращаем embedding клиенту для экономии трафика
    )
}