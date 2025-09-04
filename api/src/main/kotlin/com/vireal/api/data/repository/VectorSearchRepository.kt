package com.vireal.api.data.repository

import com.vireal.api.data.database.DatabaseFactory.dbQuery
import com.vireal.api.data.database.DataHelper
import com.vireal.shared.models.Note
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection
import java.util.UUID
import kotlin.math.sqrt

class VectorSearchRepository {

    suspend fun searchByVectorWithFilters(
        userId: Long,
        queryEmbedding: List<Float>,
        tags: List<String>? = null,
        category: String? = null,
        limit: Int = 50,
        threshold: Double = 0.2
    ): List<Pair<Note, Double>> = dbQuery {
        val connection = TransactionManager.current().connection.connection as Connection
        val embeddingString = DataHelper.formatEmbedding(queryEmbedding)

        // Проверяем, хранятся ли embeddings как vector или как text
        val checkSql = """
            SELECT data_type 
            FROM information_schema.columns 
            WHERE table_name = 'notes' AND column_name = 'embedding'
        """.trimIndent()

        val columnType = connection.createStatement().use { statement ->
            val rs = statement.executeQuery(checkSql)
            if (rs.next()) rs.getString("data_type") else "text"
        }

        val results = mutableListOf<Pair<Note, Double>>()

        if (columnType.uppercase() == "TEXT") {
            // Embeddings хранятся как JSON в TEXT поле - используем косинусное сходство в памяти
            searchByTextEmbedding(userId, queryEmbedding, tags, category, limit, threshold)
        } else {
            // Embeddings хранятся как vector - используем pgvector
            searchByVectorEmbedding(connection, userId, embeddingString, tags, category, limit, threshold)
        }
    }

    private suspend fun searchByTextEmbedding(
        userId: Long,
        queryEmbedding: List<Float>,
        tags: List<String>?,
        category: String?,
        limit: Int,
        threshold: Double
    ): List<Pair<Note, Double>> = dbQuery {
        val sql = """
            SELECT 
                id::text,
                user_id,
                content,
                created_at::text,
                tags,
                category,
                metadata,
                embedding
            FROM notes
            WHERE user_id = ?
                AND embedding IS NOT NULL
                ${if (category != null) "AND category = ?" else ""}
        """.trimIndent()

        val results = mutableListOf<Pair<Note, Double>>()
        val connection = TransactionManager.current().connection.connection as Connection

        connection.prepareStatement(sql).use { statement ->
            var paramIndex = 1
            statement.setLong(paramIndex++, userId)
            if (category != null) {
                statement.setString(paramIndex++, category)
            }

            val rs = statement.executeQuery()
            while (rs.next()) {
                val embeddingStr = rs.getString("embedding")
                if (embeddingStr != null) {
                    val embedding = DataHelper.parseEmbedding(embeddingStr)
                    val similarity = cosineSimilarity(queryEmbedding, embedding)

                    if (similarity > threshold) {
                        val noteTags = DataHelper.parseTags(rs.getString("tags"))

                        // Фильтрация по тегам
                        if (tags == null || tags.any { it in noteTags }) {
                            val note = Note(
                                id = rs.getString("id"),
                                userId = rs.getLong("user_id"),
                                content = rs.getString("content"),
                                createdAt = rs.getString("created_at"),
                                tags = noteTags,
                                category = rs.getString("category"),
                                metadata = DataHelper.mapToJsonObject(
                                    DataHelper.parseMetadata(rs.getString("metadata"))
                                ),
                                embedding = null
                            )
                            results.add(note to similarity)
                        }
                    }
                }
            }
        }

        results.sortedByDescending { it.second }.take(limit)
    }

    private fun searchByVectorEmbedding(
        connection: Connection,
        userId: Long,
        embeddingString: String,
        tags: List<String>?,
        category: String?,
        limit: Int,
        threshold: Double
    ): List<Pair<Note, Double>> {
        val conditions = mutableListOf<String>()
        conditions.add("user_id = ?")
        conditions.add("embedding IS NOT NULL")
        conditions.add("1 - (embedding <=> ?::vector) > ?")

        if (category != null) {
            conditions.add("category = ?")
        }

        val sql = """
            SELECT 
                id::text,
                user_id,
                content,
                created_at::text,
                tags,
                category,
                metadata,
                1 - (embedding <=> ?::vector) as similarity
            FROM notes
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY embedding <=> ?::vector
            LIMIT ?
        """.trimIndent()

        val results = mutableListOf<Pair<Note, Double>>()

        connection.prepareStatement(sql).use { statement ->
            var paramIndex = 1

            // SELECT similarity
            statement.setString(paramIndex++, embeddingString)

            // WHERE conditions
            statement.setLong(paramIndex++, userId)
            statement.setString(paramIndex++, embeddingString)
            statement.setDouble(paramIndex++, threshold)

            if (category != null) {
                statement.setString(paramIndex++, category)
            }

            // ORDER BY
            statement.setString(paramIndex++, embeddingString)

            // LIMIT
            statement.setInt(paramIndex++, limit)

            val rs = statement.executeQuery()
            while (rs.next()) {
                val noteTags = DataHelper.parseTags(rs.getString("tags"))

                // Фильтрация по тегам
                if (tags == null || tags.any { it in noteTags }) {
                    val note = Note(
                        id = rs.getString("id"),
                        userId = rs.getLong("user_id"),
                        content = rs.getString("content"),
                        createdAt = rs.getString("created_at"),
                        tags = noteTags,
                        category = rs.getString("category"),
                        metadata = DataHelper.mapToJsonObject(
                            DataHelper.parseMetadata(rs.getString("metadata"))
                        ),
                        embedding = null
                    )
                    val similarity = rs.getDouble("similarity")
                    results.add(note to similarity)
                }
            }
        }

        return results
    }

    suspend fun updateEmbedding(noteId: UUID, embedding: List<Float>): Boolean = dbQuery {
        val connection = TransactionManager.current().connection.connection as Connection

        // Проверяем тип колонки
        val checkSql = """
            SELECT data_type 
            FROM information_schema.columns 
            WHERE table_name = 'notes' AND column_name = 'embedding'
        """.trimIndent()

        val columnType = connection.createStatement().use { statement ->
            val rs = statement.executeQuery(checkSql)
            if (rs.next()) rs.getString("data_type") else "text"
        }

        val sql = if (columnType.uppercase() == "TEXT") {
            // Сохраняем как JSON строку
            """
                UPDATE notes 
                SET embedding = ?
                WHERE id = ?::uuid
            """.trimIndent()
        } else {
            // Сохраняем как vector
            """
                UPDATE notes 
                SET embedding = ?::vector
                WHERE id = ?::uuid
            """.trimIndent()
        }

        connection.prepareStatement(sql).use { statement ->
            if (columnType.uppercase() == "TEXT") {
                statement.setString(1, DataHelper.formatEmbedding(embedding))
            } else {
                statement.setString(1, DataHelper.formatEmbedding(embedding))
            }
            statement.setString(2, noteId.toString())
            statement.executeUpdate() > 0
        }
    }

    suspend fun getNotesWithoutEmbeddings(userId: Long, limit: Int = 10): List<Pair<UUID, String>> = dbQuery {
        val sql = """
            SELECT id::text, content
            FROM notes
            WHERE user_id = ?
                AND (embedding IS NULL OR embedding = '[]')
            ORDER BY created_at DESC
            LIMIT ?
        """.trimIndent()

        val results = mutableListOf<Pair<UUID, String>>()
        val connection = TransactionManager.current().connection.connection as Connection

        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, userId)
            statement.setInt(2, limit)

            val rs = statement.executeQuery()
            while (rs.next()) {
                val id = UUID.fromString(rs.getString("id"))
                val content = rs.getString("content")
                results.add(id to content)
            }
        }

        results
    }

    // Массовое обновление embeddings
    suspend fun updateEmbeddingsBatch(updates: List<Pair<UUID, List<Float>>>): Int = dbQuery {
        val connection = TransactionManager.current().connection.connection as Connection

        // Проверяем тип колонки
        val checkSql = """
            SELECT data_type 
            FROM information_schema.columns 
            WHERE table_name = 'notes' AND column_name = 'embedding'
        """.trimIndent()

        val columnType = connection.createStatement().use { statement ->
            val rs = statement.executeQuery(checkSql)
            if (rs.next()) rs.getString("data_type") else "text"
        }

        val sql = if (columnType.uppercase() == "TEXT") {
            "UPDATE notes SET embedding = ? WHERE id = ?::uuid"
        } else {
            "UPDATE notes SET embedding = ?::vector WHERE id = ?::uuid"
        }

        var updatedCount = 0

        connection.prepareStatement(sql).use { statement ->
            for ((noteId, embedding) in updates) {
                statement.setString(1, DataHelper.formatEmbedding(embedding))
                statement.setString(2, noteId.toString())
                statement.addBatch()
            }

            val results = statement.executeBatch()
            updatedCount = results.sum()
        }

        updatedCount
    }

    // Получение заметок, похожих на конкретную заметку
    suspend fun findSimilarNotes(
        noteId: UUID,
        userId: Long,
        limit: Int = 5,
        threshold: Double = 0.5
    ): List<Pair<Note, Double>> = dbQuery {
        // Сначала получаем embedding целевой заметки
        val targetEmbedding = getEmbeddingByNoteId(noteId, userId)

        if (targetEmbedding == null) {
            return@dbQuery emptyList<Pair<Note, Double>>()
        }

        // Используем существующий метод поиска
        searchByVectorWithFilters(
            userId = userId,
            queryEmbedding = targetEmbedding,
            limit = limit + 1, // +1 чтобы исключить саму заметку
            threshold = threshold
        ).filter { it.first.id != noteId.toString() }.take(limit)
    }

    private suspend fun getEmbeddingByNoteId(noteId: UUID, userId: Long): List<Float>? = dbQuery {
        val sql = """
            SELECT embedding
            FROM notes
            WHERE id = ?::uuid AND user_id = ?
        """.trimIndent()

        val connection = TransactionManager.current().connection.connection as Connection

        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, noteId.toString())
            statement.setLong(2, userId)

            val rs = statement.executeQuery()
            if (rs.next()) {
                val embeddingStr = rs.getString("embedding")
                if (embeddingStr != null && embeddingStr != "[]") {
                    DataHelper.parseEmbedding(embeddingStr)
                } else null
            } else null
        }
    }

    // Вычисление косинусного сходства для embeddings в памяти
    private fun cosineSimilarity(vec1: List<Float>, vec2: List<Float>): Double {
        if (vec1.size != vec2.size) return 0.0

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            normA += vec1[i] * vec1[i]
            normB += vec2[i] * vec2[i]
        }

        return if (normA == 0.0 || normB == 0.0) {
            0.0
        } else {
            dotProduct / (sqrt(normA) * sqrt(normB))
        }
    }

    // Простая функция для вычисления евклидова расстояния
    private fun euclideanDistance(vec1: List<Float>, vec2: List<Float>): Double {
        if (vec1.size != vec2.size) return Double.MAX_VALUE

        var sum = 0.0
        for (i in vec1.indices) {
            val diff = vec1[i] - vec2[i]
            sum += diff * diff
        }

        return sqrt(sum)
    }
}