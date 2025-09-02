package com.vireal.services

import com.vireal.data.models.QueryResponse

class QueryService(
    private val notesService: NotesService = NotesService(),
    private val llmService: LLMService = LLMService()
) {

    suspend fun processQuery(userId: Long, question: String): QueryResponse {
        // Используем гибридный поиск (векторный + текстовый)
        val searchResult = notesService.searchNotes(userId, question)

        // Если ничего не найдено, берем последние записи
        val notes = searchResult.notes.ifEmpty {
            println("No relevant notes found, using recent notes")
            notesService.getUserNotes(userId).take(5)
        }

        // Формируем контекст
        val context = notes.joinToString("\n\n") { note ->
            "- ${note.content}"
        }

        // Получаем ответ от LLM
        val answer = if (context.isNotEmpty()) {
            llmService.generateAnswer(context, question)
        } else {
            "К сожалению, в базе знаний пока нет информации по вашему вопросу. Попробуйте добавить соответствующие записи через команду 'Добавь: [текст]'"
        }

        return QueryResponse(
            answer = answer,
            sources = notes.map { it.content.take(100) + "..." }
        )
    }
}