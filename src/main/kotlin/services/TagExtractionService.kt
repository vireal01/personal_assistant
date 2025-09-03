package com.vireal.services

class TagExtractionService {

    // Паттерны для быстрого локального извлечения тегов
    private val categoryPatterns = mapOf(
        "work" to listOf("встреча", "проект", "задача", "работа", "meeting", "project", "task"),
        "personal" to listOf("личное", "семья", "друзья", "хобби", "personal", "family"),
        "finance" to listOf("деньги", "бюджет", "расходы", "доходы", "money", "budget"),
        "tech" to listOf("код", "программирование", "разработка", "kotlin", "api", "база данных"),
        "health" to listOf("здоровье", "спорт", "врач", "лекарство", "health", "doctor")
    )

    fun extractTagsAndCategory(content: String): Pair<List<String>, String?> {
        val contentLower = content.lowercase()
        val tags = mutableSetOf<String>()
        var category: String? = null

        // Определяем категорию и базовые теги
        categoryPatterns.forEach { (cat, patterns) ->
            if (patterns.any { contentLower.contains(it) }) {
                if (category == null) category = cat
                tags.add(cat)
            }
        }

        // Извлекаем дополнительные теги из частых слов
        val words = content.split(Regex("\\s+"))
            .filter { it.length > 3 }
            .map { it.lowercase().trim(',', '.', '!', '?') }
            .groupingBy { it }
            .eachCount()
            .filter { it.value > 1 } // Слова, встречающиеся больше 1 раза
            .keys
            .take(3)

        tags.addAll(words)

        return tags.toList() to category
    }
}