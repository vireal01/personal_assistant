package com.vireal.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object Notes : Table("notes") {
    val id = uuid("id").autoGenerate()
    val userId = long("user_id")
    val content = text("content")
    val createdAt = timestamp("created_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}