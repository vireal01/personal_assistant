package com.vireal.data.repository

import com.vireal.data.database.DatabaseFactory.dbQuery
import com.vireal.data.models.Note
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection
import java.util.UUID

class VectorSearchRepository {

    suspend fun searchByVector(
        userId: Long,
        queryEmbedding: List<Float>,
        limit: Int = 5,
        threshold: Double = 0.7
    ): List<Pair<Note, Double>> = dbQuery {
        val embeddingString = queryEmbedding.joinToString(",", "[", "]")

        val sql = """
            SELECT 
                id::text,
                user_id,
                content,
                created_at::text,
                1 - (embedding <=> ?::vector) as similarity
            FROM notes
            WHERE user_id = ?
                AND embedding IS NOT NULL
                AND 1 - (embedding <=> ?::vector) > ?
            ORDER BY embedding <=> ?::vector
            LIMIT ?
        """.trimIndent()

        val results = mutableListOf<Pair<Note, Double>>()

        val connection = TransactionManager.current().connection.connection as Connection

        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, embeddingString)
            statement.setLong(2, userId)
            statement.setString(3, embeddingString)
            statement.setDouble(4, threshold)
            statement.setString(5, embeddingString)
            statement.setInt(6, limit)

            val rs = statement.executeQuery()
            while (rs.next()) {
                val note = Note(
                    id = rs.getString("id"),
                    userId = rs.getLong("user_id"),
                    content = rs.getString("content"),
                    createdAt = rs.getString("created_at")
                )
                val similarity = rs.getDouble("similarity")
                results.add(note to similarity)
            }
        }

        results
    }

    suspend fun updateEmbedding(noteId: UUID, embedding: List<Float>): Boolean = dbQuery {
        val embeddingString = embedding.joinToString(",", "[", "]")

        val sql = """
            UPDATE notes 
            SET embedding = ?::vector
            WHERE id = ?::uuid
        """.trimIndent()

        val connection = TransactionManager.current().connection.connection as Connection

        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, embeddingString)
            statement.setString(2, noteId.toString())
            statement.executeUpdate() > 0
        }
    }

    suspend fun getNotesWithoutEmbeddings(userId: Long, limit: Int = 10): List<Pair<UUID, String>> = dbQuery {
        val sql = """
            SELECT id::text, content
            FROM notes
            WHERE user_id = ?
                AND embedding IS NULL
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
}