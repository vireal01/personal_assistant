package com.vireal.api.data.database

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table

class VectorColumnType(private val dimensions: Int) : ColumnType() {
  override fun sqlType(): String = "vector($dimensions)"

  override fun valueFromDB(value: Any): Any = when (value) {
    is FloatArray -> value
    is String -> value
      .removePrefix("[")
      .removeSuffix("]")
      .split(",")
      .map { it.trim().toFloat() }
      .toFloatArray()

    else -> error("Unexpected value of type Vector: $value of ${value::class.qualifiedName}")
  }

  override fun notNullValueToDB(value: Any): Any = when (value) {
    is FloatArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",")
    else -> error("Only FloatArray is supported for vector column, got: $value")
  }

  override fun nonNullValueToString(value: Any): String = notNullValueToDB(value).toString()
}

fun Table.vector(name: String, dimensions: Int): Column<FloatArray> =
  registerColumn(name, VectorColumnType(dimensions))
