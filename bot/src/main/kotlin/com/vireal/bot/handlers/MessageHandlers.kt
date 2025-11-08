package com.vireal.bot.handlers

import com.vireal.bot.service.BotService
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.utils.row
import org.slf4j.LoggerFactory

object MessageHandlers {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val userStates = mutableMapOf<Long, UserState>()

    data class UserState(
        var lastMessage: String? = null,
        var waitingFor: WaitingState? = null
    )

    enum class WaitingState {
        NOTE_TEXT,
        SEARCH_QUERY,
        QUESTION
    }

    suspend fun register(context: BehaviourContext, botService: BotService) = with(context) {

        // Обработка обычных текстовых сообщений
        onText { message ->
            val userId = message.chat.id.chatId
            val text = message.content.text

            // Игнорируем команды
            if (text.startsWith("/")) return@onText

            // Обработка кнопок клавиатуры
            when (text) {
                "📝 Добавить заметку" -> {
                    send(message.chat, "Отправьте текст заметки:")
                    userStates[userId] = UserState(waitingFor = WaitingState.NOTE_TEXT)
                }
//
//                "🔍 Поиск" -> {
//                    send(message.chat, "Введите поисковый запрос:")
//                    userStates[userId] = UserState(waitingFor = WaitingState.SEARCH_QUERY)
//                }

                "❓ Задать вопрос" -> {
                    send(message.chat, "Задайте ваш вопрос:")
                    userStates[userId] = UserState(waitingFor = WaitingState.QUESTION)
                }

//                "📚 Мои заметки" -> {
//                    handleMyNotes(message, botService)
//                }
//
//                "🏷 Теги" -> {
//                    handleTags(message, botService)
//                }
//
//                "📊 Статистика" -> {
//                    handleStats(message, botService)
//                }

                else -> {
                    // Проверяем состояние пользователя
                    val state = userStates[userId]

                    when (state?.waitingFor) {
                        WaitingState.NOTE_TEXT -> {
                            handleAddNote(message, text, botService)
                            userStates.remove(userId)
                        }

                        WaitingState.SEARCH_QUERY -> {
                            handleSearch(message, text, botService)
                            userStates.remove(userId)
                        }

                        WaitingState.QUESTION -> {
                            handleQuestion(message, text, botService)
                            userStates.remove(userId)
                        }

                        else -> {
                            // Предлагаем действия для текста
                            userStates[userId] = UserState(lastMessage = text)

                            send(
                                message.chat,
                                "Что сделать с этим текстом?",
                                replyMarkup = inlineKeyboard {
                                    row {
                                        dataButton("📝 Сохранить заметку", "save_note")
                                        dataButton("❓ Задать вопрос", "ask_question")
                                    }
                                    row {
                                        dataButton("❌ Отмена", "cancel")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun BehaviourContext.handleAddNote(
        message: CommonMessage<TextContent>,
        text: String,
        botService: BotService
    ) {
        val userId = message.chat.id.chatId
        val tempMsg = send(message.chat, "⏳ Сохраняю заметку...")

        try {
            val response = botService.createNote(userId, text)

            if (response.success) {
                editMessageText(
                    message.chat,
                    tempMsg.messageId,
                    "✅ Заметка сохранена!"
                )
            } else {
                editMessageText(
                    message.chat,
                    tempMsg.messageId,
                    "❌ Ошибка: ${response.message}"
                )
            }
        } catch (e: Exception) {
            logger.error("Error adding note", e)
            editMessageText(
                message.chat,
                tempMsg.messageId,
                "❌ Ошибка сохранения"
            )
        }
    }

    private suspend fun BehaviourContext.handleSearch(
        message: CommonMessage<TextContent>,
        query: String,
        botService: BotService
    ) {
        val userId = message.chat.id.chatId
        val tempMsg = send(message.chat, "🔍 Ищу...")

        try {
            val results = botService.searchNotes(userId, query)

            val resultText = if (results.notes.isEmpty()) {
                "Ничего не найдено по запросу: $query"
            } else {
                buildString {
                    appendLine("Найдено ${results.totalFound} заметок:")
                    results.notes.take(3).forEachIndexed { i, note ->
                        appendLine("${i+1}. ${note.content.take(100)}")
                    }
                }
            }

            editMessageText(message.chat, tempMsg.messageId, resultText)
        } catch (e: Exception) {
            logger.error("Error searching", e)
            editMessageText(message.chat, tempMsg.messageId, "❌ Ошибка поиска")
        }
    }

    private suspend fun BehaviourContext.handleQuestion(
        message: CommonMessage<TextContent>,
        question: String,
        botService: BotService
    ) {
        val userId = message.chat.id.chatId
        val tempMsg = send(message.chat, "🤔 Анализирую...")

        try {
            val response = botService.askQuestion(userId, question)
            editMessageText(
                message.chat,
                tempMsg.messageId,
                response.answer
            )
        } catch (e: Exception) {
            logger.error("Error processing question", e)
            editMessageText(
                message.chat,
                tempMsg.messageId,
                "❌ Ошибка обработки вопроса"
            )
        }
    }

    private suspend fun BehaviourContext.handleMyNotes(
        message: CommonMessage<TextContent>,
        botService: BotService
    ) {
        val userId = message.chat.id.chatId

        try {
            val notes = botService.getUserNotes(userId, 5)

            val text = if (notes.isEmpty()) {
                "У вас пока нет заметок"
            } else {
                buildString {
                    appendLine("📚 Ваши последние заметки:")
                    notes.forEachIndexed { i, note ->
                        appendLine("${i+1}. ${note.content.take(100)}")
                    }
                }
            }

            send(message.chat, text)
        } catch (e: Exception) {
            logger.error("Error getting notes", e)
            send(message.chat, "❌ Ошибка получения заметок")
        }
    }

    private suspend fun BehaviourContext.handleTags(
        message: CommonMessage<TextContent>,
        botService: BotService
    ) {
        val userId = message.chat.id.chatId

        try {
            val tags = botService.getUserTags(userId)

            val text = if (tags.isEmpty()) {
                "У вас пока нет тегов"
            } else {
                "🏷 Ваши теги:\n" + tags.joinToString(", ") { "#$it" }
            }

            send(message.chat, text)
        } catch (e: Exception) {
            logger.error("Error getting tags", e)
            send(message.chat, "❌ Ошибка получения тегов")
        }
    }

    private suspend fun BehaviourContext.handleStats(
        message: CommonMessage<TextContent>,
        botService: BotService
    ) {
        val userId = message.chat.id.chatId

        try {
            val count = botService.getNotesCount(userId)
            val tags = botService.getUserTags(userId)
            val categories = botService.getCategoryStats(userId)

            val text = buildString {
                appendLine("📊 Статистика:")
                appendLine("📝 Заметок: $count")
                appendLine("🏷 Тегов: ${tags.size}")
                appendLine("📁 Категорий: ${categories.size}")
            }

            send(message.chat, text)
        } catch (e: Exception) {
            logger.error("Error getting stats", e)
            send(message.chat, "❌ Ошибка получения статистики")
        }
    }

    fun getUserState(userId: Long): UserState? = userStates[userId]
    fun removeUserState(userId: Long) = userStates.remove(userId)
}