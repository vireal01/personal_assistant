package com.vireal.api.data.database

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject

/**
 * ColumnType для поддержки PostgreSQL JSONB
 */
class JsonbColumnType : ColumnType() {
  override fun sqlType(): String = "jsonb"

  override fun valueFromDB(value: Any): String = when (value) {
    is PGobject -> value.value ?: "{}"
    is String -> value
    else -> error("Unexpected value of type JSONB: $value of ${value::class.qualifiedName}")
  }

  override fun notNullValueToDB(value: Any): Any {
    val jsonString = when (value) {
      is String -> value
      else -> value.toString()
    }

    return PGobject().apply {
      type = "jsonb"
      this.value = jsonString
    }
  }

  override fun nonNullValueToString(value: Any): String = when (value) {
    is String -> value
    else -> value.toString()
  }
}

/**
 * Extension функция для создания JSONB колонки
 */
fun Table.jsonb(name: String): Column<String> =
  registerColumn(name, JsonbColumnType())
