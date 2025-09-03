package com.vireal.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object Notes : Table("notes") {
    val id = uuid("id").autoGenerate()
    val userId = long("user_id")
    val content = text("content")
    val createdAt = timestamp("created_at").default(Instant.now())

    // Храним сложные типы как строки
    val tags = text("tags").default("[]")           // JSON array
    val category = varchar("category", 50).nullable()
    val metadata = text("metadata").default("{}")    // JSON object
    val embedding = text("embedding").nullable()     // JSON array of floats

    override val primaryKey = PrimaryKey(id)
}

// Таблица для отслеживания миграций
object SchemaMigrations : Table("schema_migrations") {
    val version = integer("version")
    val appliedAt = timestamp("applied_at").default(Instant.now())

    override val primaryKey = PrimaryKey(version)
}