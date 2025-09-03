package com.vireal.data.database

import kotlinx.serialization.json.*

object DataHelper {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ===== TAGS =====
    fun parseTags(value: String): List<String> {
        return try {
            when {
                value.isEmpty() || value == "[]" -> emptyList()
                value.startsWith("[") -> {
                    // JSON array format: ["tag1", "tag2"]
                    json.decodeFromString<List<String>>(value)
                }
                value.startsWith("{") -> {
                    // PostgreSQL array format: {tag1,tag2}
                    value.trim('{', '}')
                        .split(',')
                        .map { it.trim('"', ' ') }
                        .filter { it.isNotEmpty() }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            println("Error parsing tags: ${e.message}")
            emptyList()
        }
    }

    fun formatTags(tags: List<String>): String {
        return json.encodeToString(tags)
    }

    // ===== METADATA =====
    fun parseMetadata(value: String): Map<String, Any> {
        return try {
            when {
                value.isEmpty() || value == "{}" -> emptyMap()
                else -> {
                    val jsonObject = json.decodeFromString<JsonObject>(value)
                    jsonObject.entries.associate { (key, element) ->
                        key to when (element) {
                            is JsonPrimitive -> {
                                when {
                                    element.isString -> element.content
                                    element.booleanOrNull != null -> element.boolean
                                    element.intOrNull != null -> element.int
                                    element.doubleOrNull != null -> element.double
                                    else -> element.content
                                }
                            }
                            is JsonArray -> element.toString()
                            is JsonObject -> element.toString()
                            else -> element.toString()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Error parsing metadata: ${e.message}")
            emptyMap()
        }
    }

    fun formatMetadata(metadata: Map<String, Any>): String {
        return try {
            val jsonObject = buildJsonObject {
                metadata.forEach { (key, value) ->
                    when (value) {
                        is String -> put(key, value)
                        is Number -> put(key, value)
                        is Boolean -> put(key, value)
                        is List<*> -> putJsonArray(key) {
                            value.forEach { item ->
                                when (item) {
                                    is String -> add(item)
                                    is Number -> add(item)
                                    is Boolean -> add(item)
                                    else -> add(item.toString())
                                }
                            }
                        }
                        else -> put(key, value.toString())
                    }
                }
            }
            json.encodeToString(jsonObject)
        } catch (e: Exception) {
            println("Error formatting metadata: ${e.message}")
            "{}"
        }
    }

    // ===== EMBEDDING =====
    fun parseEmbedding(value: String): List<Float> {
        return try {
            when {
                value.isEmpty() || value == "[]" -> emptyList()
                value.startsWith("[") -> {
                    // JSON array format: [0.1, 0.2, 0.3]
                    value.trim('[', ']')
                        .split(',')
                        .map { it.trim().toFloat() }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            println("Error parsing embedding: ${e.message}")
            emptyList()
        }
    }

    fun formatEmbedding(embedding: List<Float>): String {
        return embedding.joinToString(",", "[", "]")
    }

    // ===== Вспомогательные функции для работы с JsonObject =====
    fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
        return jsonObject.entries.associate { (key, element) ->
            key to when (element) {
                is JsonPrimitive -> {
                    when {
                        element.isString -> element.content
                        element.booleanOrNull != null -> element.boolean
                        element.intOrNull != null -> element.int
                        element.doubleOrNull != null -> element.double
                        else -> element.content
                    }
                }
                else -> element.toString()
            }
        }
    }

    fun mapToJsonObject(map: Map<String, Any>): JsonObject {
        return buildJsonObject {
            map.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value)
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }
    }
}