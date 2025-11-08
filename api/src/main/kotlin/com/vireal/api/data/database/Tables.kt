package com.vireal.api.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Основная таблица заметок
 * Оптимизирована для работы с большими объемами данных
 */
object Notes : Table("notes") {
  val id = uuid("id").autoGenerate()
  val userId = long("user_id")
  val content = text("content")
  val createdAt = timestamp("created_at").default(Instant.now())

  // Метаданные хранятся в оптимизированных форматах
  val tags = text("tags").default("[]")           // JSON array для GIN индекса
  val category = varchar("category", 50).nullable()
  val metadata = text("metadata").default("{}")    // JSON object
  val embedding = vector("embedding", 1536).nullable()     // JSON array of floats

  override val primaryKey = PrimaryKey(id)
}

/**
 * Таблица для отслеживания примененных миграций
 */
object SchemaMigrations : Table("schema_migrations") {
  val version = integer("version")
  val appliedAt = timestamp("applied_at").default(Instant.now())

  override val primaryKey = PrimaryKey(version)
}

/**
 * Кэш-таблица для часто используемых поисковых запросов
 * Позволяет избежать повторных вычислений для популярных запросов
 */
object NoteSearchCache : Table("note_search_cache") {
  val id = uuid("id").autoGenerate()
  val userId = long("user_id")
  val queryHash = varchar("query_hash", 64)  // SHA-256 hash запроса
  val queryText = text("query_text")         // Оригинальный запрос для отладки
  val resultIds = text("result_ids")         // JSON array с ID найденных заметок
  val scores = text("scores")                // JSON array со scores
  val createdAt = timestamp("created_at").default(Instant.now())
  val expiresAt = timestamp("expires_at")    // TTL для кэша
  val hitCount = integer("hit_count").default(0)  // Счетчик использования

  override val primaryKey = PrimaryKey(id)

  // Составной индекс для быстрого поиска
  init {
    index(true, userId, queryHash)  // Уникальный индекс
    index(false, expiresAt)          // Для очистки устаревших записей
  }
}
