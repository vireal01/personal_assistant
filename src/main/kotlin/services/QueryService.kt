package com.vireal.services


import com.vireal.data.models.QueryResponse

class QueryService(
    private val notesService: NotesService = NotesService(),
    private val llmService: LLMService = LLMService()
) {

    suspend fun processQuery(userId: Long, question: String): QueryResponse {
        // Извлекаем ключевые слова из вопроса (простой подход)
        val keywords = extractKeywords(question)

        // Ищем релевантные записи
        val searchResult = notesService.searchNotes(userId, keywords)

        // Формируем контекст из найденных записей
        val context = if (searchResult.notes.isNotEmpty()) {
            searchResult.notes.joinToString("\n\n") { note ->
                "- ${note.content}"
            }
        } else {
            ""
        }

        // Получаем ответ от LLM
        val answer = if (context.isNotEmpty()) {
            llmService.generateAnswer(context, question)
        } else {
            "К сожалению, в базе знаний пока нет информации по вашему вопросу. Попробуйте добавить соответствующие записи через команду 'Добавь: [текст]'"
        }

        return QueryResponse(
            answer = answer,
            sources = searchResult.notes.map { it.content.take(100) + "..." }
        )
    }

    private fun extractKeywords(text: String): String {
        // Расширенный список стоп-слов для русского и английского языков
        val stopWords = setOf(
            // Русские стоп-слова
            "что", "как", "какой", "какая", "какие", "где", "когда", "почему",
            "зачем", "можно", "ли", "есть", "это", "такое", "в", "на", "с",
            "у", "к", "по", "для", "из", "от", "до", "о", "об", "про",
            "меня", "тебя", "его", "её", "нас", "вас", "их", "мой", "твой",
            "наш", "ваш", "их", "я", "ты", "он", "она", "мы", "вы", "они",
            "быть", "иметь", "делать", "говорить", "знать", "хотеть", "мочь",
            // Английские стоп-слова
            "what", "how", "where", "when", "why", "who", "which", "is", "are", "was", "were",
            "am", "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "can", "must", "shall", "a", "an", "the", "and", "or",
            "but", "in", "on", "at", "to", "for", "of", "with", "by", "from", "up", "down", "out",
            "off", "over", "under", "again", "further", "then", "once", "here", "there", "when",
            "where", "why", "how", "all", "any", "both", "each", "few", "more", "most", "other",
            "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very",
            "my", "your", "his", "her", "its", "our", "their", "me", "you", "him", "us", "them"
        )

        return text.lowercase()
            .replace(Regex("[?!.,;:()]"), " ") // Убираем знаки препинания
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in stopWords } // Изменили с > 2 на > 1
            .joinToString(" ")
    }
}