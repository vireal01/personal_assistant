package com.vireal.api.data.repository

import com.vireal.api.data.database.DatabaseFactory.dbQuery
import com.vireal.api.data.database.DataHelper
import com.vireal.shared.models.Note
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection
import java.util.UUID

class VectorSearchRepository {

  companion object {
    // Константы для оптимизации поиска
    private const val DEFAULT_EF_SEARCH = 100  // Параметр поиска для HNSW
    private const val MAX_BATCH_SIZE = 1000    // Максимальный размер батча
    private const val VECTOR_DIMENSION = 1536  // Размерность вектора OpenAI
  }

  /**
   * Масштабируемый векторный поиск с использованием pgvector
   * Использует HNSW индекс для O(log n) сложности вместо O(n)
   */
  suspend fun searchByVector(
    userId: Long,
    queryEmbedding: List<Float>,
    limit: Int = 50,
    threshold: Double = 0.0
  ): List<Pair<Note, Double>> = dbQuery {
    val connection = TransactionManager.current().connection.connection as Connection

    // Форматируем вектор для PostgreSQL
    val embeddingString = formatEmbedding(queryEmbedding)

    // Оптимизированный запрос с использованием HNSW индекса
    val sql = """
            WITH vector_search AS (
                SELECT
                    id,
                    user_id,
                    content,
                    created_at,
                    tags,
                    category,
                    metadata,
                    embedding,
                    1 - (embedding <=> ?::vector($VECTOR_DIMENSION)) as similarity
                FROM notes
                WHERE user_id = ?
                    AND embedding IS NOT NULL
                    AND 1 - (embedding <=> ?::vector($VECTOR_DIMENSION)) > ?
                ORDER BY embedding <=> ?::vector($VECTOR_DIMENSION)
                LIMIT ?
            )
            SELECT * FROM vector_search
            ORDER BY similarity DESC
        """.trimIndent()

    val results = mutableListOf<Pair<Note, Double>>()

    connection.prepareStatement(sql).use { statement ->
      // Устанавливаем параметры
      statement.setString(1, embeddingString)  // Для SELECT similarity
      statement.setLong(2, userId)
      statement.setString(3, embeddingString)  // Для WHERE threshold
      statement.setDouble(4, threshold)
      statement.setString(5, embeddingString)  // Для ORDER BY
      statement.setInt(6, limit)

      val rs = statement.executeQuery()
      while (rs.next()) {
        val note = Note(
          id = rs.getString("id"),
          userId = rs.getLong("user_id"),
          content = rs.getString("content"),
          createdAt = rs.getTimestamp("created_at").toInstant().toString(),
          tags = DataHelper.parseTags(rs.getString("tags")),
          category = rs.getString("category"),
          metadata = DataHelper.mapToJsonObject(
            DataHelper.parseMetadata(rs.getString("metadata") ?: "{}")
          ),
          embedding = null  // Не возвращаем embedding клиенту
        )
        val similarity = rs.getDouble("similarity")
        results.add(note to similarity)
      }
    }

    results
  }

  /**
   * Векторный поиск с дополнительными фильтрами
   * Использует составные индексы для эффективной фильтрации
   */
  suspend fun searchByVectorWithFilters(
    userId: Long,
    queryEmbedding: List<Float>,
    tags: List<String>? = null,
    category: String? = null,
    limit: Int = 50,
    threshold: Double = 0.0
  ): List<Pair<Note, Double>> = dbQuery {
    val connection = TransactionManager.current().connection.connection as Connection
    val embeddingString = formatEmbedding(queryEmbedding)

    // Строим динамический запрос с фильтрами
    val whereConditions = mutableListOf<String>()
    whereConditions.add("user_id = ?")
    whereConditions.add("embedding IS NOT NULL")
    whereConditions.add("1 - (embedding <=> ?::vector($VECTOR_DIMENSION)) > ?")

    val params = mutableListOf<Any>()
    params.add(embeddingString)  // Для similarity в SELECT
    params.add(userId)
    params.add(embeddingString)  // Для threshold в WHERE
    params.add(threshold)

    if (category != null) {
      whereConditions.add("category = ?")
      params.add(category)
    }

    // Для тегов используем GIN индекс
    if (!tags.isNullOrEmpty()) {
      val tagConditions = tags.map { "tags @> ?::jsonb" }
      whereConditions.add("(${tagConditions.joinToString(" OR ")})")
      tags.forEach { tag ->
        params.add("[\"$tag\"]")  // JSON array format
      }
    }

    params.add(embeddingString)  // Для ORDER BY
    params.add(limit)

    val sql = """
            WITH filtered_search AS (
                SELECT
                    id,
                    user_id,
                    content,
                    created_at,
                    tags,
                    category,
                    metadata,
                    embedding,
                    1 - (embedding <=> ?::vector($VECTOR_DIMENSION)) as similarity
                FROM notes
                WHERE ${whereConditions.joinToString(" AND ")}
                ORDER BY embedding <=> ?::vector($VECTOR_DIMENSION)
                LIMIT ?
            )
            SELECT * FROM filtered_search
            ORDER BY similarity DESC
        """.trimIndent()

    val results = mutableListOf<Pair<Note, Double>>()

    connection.prepareStatement(sql).use { statement ->
      params.forEachIndexed { index, param ->
        when (param) {
          is String -> statement.setString(index + 1, param)
          is Long -> statement.setLong(index + 1, param)
          is Int -> statement.setInt(index + 1, param)
          is Double -> statement.setDouble(index + 1, param)
        }
      }

      val rs = statement.executeQuery()
      while (rs.next()) {
        val note = Note(
          id = rs.getString("id"),
          userId = rs.getLong("user_id"),
          content = rs.getString("content"),
          createdAt = rs.getTimestamp("created_at").toInstant().toString(),
          tags = DataHelper.parseTags(rs.getString("tags")),
          category = rs.getString("category"),
          metadata = DataHelper.mapToJsonObject(
            DataHelper.parseMetadata(rs.getString("metadata") ?: "{}")
          ),
          embedding = null
        )
        val similarity = rs.getDouble("similarity")
        results.add(note to similarity)
      }
    }

    results
  }

  /**
   * Гибридный поиск: векторный + текстовый с весами
   */
  suspend fun hybridSearch(
    userId: Long,
    queryEmbedding: List<Float>,
    queryText: String,
    vectorWeight: Double = 0.7,
    textWeight: Double = 0.3,
    limit: Int = 50
  ): List<Pair<Note, Double>> = dbQuery {
    val connection = TransactionManager.current().connection.connection as Connection
    val embeddingString = formatEmbedding(queryEmbedding)

    // Гибридный запрос с RRF (Reciprocal Rank Fusion)
    val sql = """
            WITH vector_results AS (
                SELECT
                    id,
                    1 - (embedding <=> ?::vector($VECTOR_DIMENSION)) as vector_score,
                    ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector($VECTOR_DIMENSION)) as vector_rank
                FROM notes
                WHERE user_id = ? AND embedding IS NOT NULL
                ORDER BY embedding <=> ?::vector($VECTOR_DIMENSION)
                LIMIT 100
            ),
            text_results AS (
                SELECT
                    id,
                    ts_rank_cd(to_tsvector('english', content), plainto_tsquery('english', ?)) as text_score,
                    ROW_NUMBER() OVER (
                        ORDER BY ts_rank_cd(to_tsvector('english', content), plainto_tsquery('english', ?)) DESC
                    ) as text_rank
                FROM notes
                WHERE user_id = ?
                    AND to_tsvector('english', content) @@ plainto_tsquery('english', ?)
                ORDER BY text_score DESC
                LIMIT 100
            ),
            combined AS (
                SELECT
                    COALESCE(v.id, t.id) as id,
                    COALESCE(v.vector_score * ?, 0) + COALESCE(t.text_score * ?, 0) as combined_score,
                    v.vector_rank,
                    t.text_rank
                FROM vector_results v
                FULL OUTER JOIN text_results t ON v.id = t.id
            )
            SELECT
                n.*,
                c.combined_score
            FROM combined c
            JOIN notes n ON n.id = c.id
            ORDER BY c.combined_score DESC
            LIMIT ?
        """.trimIndent()

    val results = mutableListOf<Pair<Note, Double>>()

    connection.prepareStatement(sql).use { statement ->
      var idx = 1
      // Vector search parameters
      statement.setString(idx++, embeddingString)
      statement.setString(idx++, embeddingString)
      statement.setLong(idx++, userId)
      statement.setString(idx++, embeddingString)

      // Text search parameters
      statement.setString(idx++, queryText)
      statement.setString(idx++, queryText)
      statement.setLong(idx++, userId)
      statement.setString(idx++, queryText)

      // Weights
      statement.setDouble(idx++, vectorWeight)
      statement.setDouble(idx++, textWeight)

      // Limit
      statement.setInt(idx++, limit)

      val rs = statement.executeQuery()
      while (rs.next()) {
        val note = Note(
          id = rs.getString("id"),
          userId = rs.getLong("user_id"),
          content = rs.getString("content"),
          createdAt = rs.getTimestamp("created_at").toInstant().toString(),
          tags = DataHelper.parseTags(rs.getString("tags")),
          category = rs.getString("category"),
          metadata = DataHelper.mapToJsonObject(
            DataHelper.parseMetadata(rs.getString("metadata") ?: "{}")
          ),
          embedding = null
        )
        val score = rs.getDouble("combined_score")
        results.add(note to score)
      }
    }

    results
  }

  /**
   * Batch обновление embeddings с оптимизацией
   */
  suspend fun updateEmbeddingsBatch(
    updates: List<Pair<UUID, List<Float>>>
  ): Int = dbQuery {
    if (updates.isEmpty()) return@dbQuery 0

    val connection = TransactionManager.current().connection.connection as Connection
    var totalUpdated = 0

    // Обрабатываем батчами для оптимизации
    updates.chunked(MAX_BATCH_SIZE).forEach { batch ->
      // Используем COPY для максимальной производительности при больших объемах
      if (batch.size > 100) {
        totalUpdated += updateEmbeddingsWithCopy(connection, batch)
      } else {
        // Для небольших батчей используем обычный batch update
        totalUpdated += updateEmbeddingsWithBatch(connection, batch)
      }
    }

    // Обновляем статистику для оптимизатора после больших обновлений
    if (totalUpdated > 1000) {
      connection.createStatement().use { it.execute("ANALYZE notes (embedding)") }
    }

    totalUpdated
  }

  /**
   * Обновление одного embedding
   */
  suspend fun updateEmbedding(
    noteId: UUID,
    embedding: List<Float>
  ): Boolean = dbQuery {
    val connection = TransactionManager.current().connection.connection as Connection
    val embeddingString = formatEmbedding(embedding)

    val sql = """
            UPDATE notes
            SET embedding = ?::vector($VECTOR_DIMENSION)
            WHERE id = ?::uuid
        """.trimIndent()

    connection.prepareStatement(sql).use { statement ->
      statement.setString(1, embeddingString)
      statement.setString(2, noteId.toString())
      statement.executeUpdate() > 0
    }
  }

  /**
   * Получение заметок без embeddings для batch обработки
   */
  suspend fun getNotesWithoutEmbeddings(
    userId: Long,
    limit: Int = 100
  ): List<Pair<UUID, String>> = dbQuery {
    val connection = TransactionManager.current().connection.connection as Connection

    val sql = """
            SELECT id, content
            FROM notes
            WHERE user_id = ?
                AND embedding IS NULL
            ORDER BY created_at DESC
            LIMIT ?
        """.trimIndent()

    val results = mutableListOf<Pair<UUID, String>>()

    connection.prepareStatement(sql).use { statement ->
      statement.setLong(1, userId)
      statement.setInt(2, limit)

      val rs = statement.executeQuery()
      while (rs.next()) {
        results.add(
          UUID.fromString(rs.getString("id")) to rs.getString("content")
        )
      }
    }

    results
  }

  /**
   * Поиск похожих заметок для конкретной заметки
   */
  suspend fun findSimilarNotes(
    noteId: UUID,
    userId: Long,
    limit: Int = 5,
    threshold: Double = 0.5
  ): List<Pair<Note, Double>> = dbQuery {
    val connection = TransactionManager.current().connection.connection as Connection

    // Используем self-join для поиска похожих
    val sql = """
            WITH target_note AS (
                SELECT embedding
                FROM notes
                WHERE id = ?::uuid AND user_id = ?
            )
            SELECT
                n.id,
                n.user_id,
                n.content,
                n.created_at,
                n.tags,
                n.category,
                n.metadata,
                1 - (n.embedding <=> t.embedding) as similarity
            FROM notes n, target_note t
            WHERE n.user_id = ?
                AND n.id != ?::uuid
                AND n.embedding IS NOT NULL
                AND 1 - (n.embedding <=> t.embedding) > ?
            ORDER BY n.embedding <=> t.embedding
            LIMIT ?
        """.trimIndent()

    val results = mutableListOf<Pair<Note, Double>>()

    connection.prepareStatement(sql).use { statement ->
      statement.setString(1, noteId.toString())
      statement.setLong(2, userId)
      statement.setLong(3, userId)
      statement.setString(4, noteId.toString())
      statement.setDouble(5, threshold)
      statement.setInt(6, limit)

      val rs = statement.executeQuery()
      while (rs.next()) {
        val note = Note(
          id = rs.getString("id"),
          userId = rs.getLong("user_id"),
          content = rs.getString("content"),
          createdAt = rs.getTimestamp("created_at").toInstant().toString(),
          tags = DataHelper.parseTags(rs.getString("tags")),
          category = rs.getString("category"),
          metadata = DataHelper.mapToJsonObject(
            DataHelper.parseMetadata(rs.getString("metadata") ?: "{}")
          ),
          embedding = null
        )
        val similarity = rs.getDouble("similarity")
        results.add(note to similarity)
      }
    }

    results
  }

  /**
   * Форматирование embedding для PostgreSQL vector типа
   */
  private fun formatEmbedding(embedding: List<Float>): String {
    return embedding.joinToString(",", "[", "]")
  }

  /**
   * Batch update с использованием prepared statements
   */
  private fun updateEmbeddingsWithBatch(
    connection: Connection,
    batch: List<Pair<UUID, List<Float>>>
  ): Int {
    val sql = "UPDATE notes SET embedding = ?::vector($VECTOR_DIMENSION) WHERE id = ?::uuid"
    var updated = 0

    connection.prepareStatement(sql).use { statement ->
      batch.forEach { (noteId, embedding) ->
        statement.setString(1, formatEmbedding(embedding))
        statement.setString(2, noteId.toString())
        statement.addBatch()
      }

      val results = statement.executeBatch()
      updated = results.sum()
    }

    return updated
  }

  /**
   * Оптимизированное обновление через COPY для больших объемов
   */
  private fun updateEmbeddingsWithCopy(
    connection: Connection,
    batch: List<Pair<UUID, List<Float>>>
  ): Int {
    // Создаем временную таблицу
    val tempTable = "temp_embeddings_${System.currentTimeMillis()}"

    try {
      connection.createStatement().use { statement ->
        statement.execute(
          """
                    CREATE TEMP TABLE $tempTable (
                        id UUID,
                        embedding vector($VECTOR_DIMENSION)
                    )
                """
        )
      }

      // Используем COPY для быстрой загрузки
      val copyManager = (connection as org.postgresql.PGConnection).copyAPI
      val copyIn = copyManager.copyIn(
        "COPY $tempTable (id, embedding) FROM STDIN WITH (FORMAT CSV)"
      )

      batch.forEach { (noteId, embedding) ->
        val line = "$noteId,\"${formatEmbedding(embedding)}\"\n"
        copyIn.writeToCopy(line.toByteArray(), 0, line.length)
      }

      copyIn.endCopy()

      // Обновляем основную таблицу из временной
      connection.createStatement().use { statement ->
        val updated = statement.executeUpdate(
          """
                    UPDATE notes n
                    SET embedding = t.embedding
                    FROM $tempTable t
                    WHERE n.id = t.id
                """
        )

        // Удаляем временную таблицу
        statement.execute("DROP TABLE $tempTable")

        return updated
      }
    } catch (e: Exception) {
      println("Error in batch COPY update: ${e.message}")
      // Fallback на обычный batch update
      return updateEmbeddingsWithBatch(connection, batch)
    }
  }
}
