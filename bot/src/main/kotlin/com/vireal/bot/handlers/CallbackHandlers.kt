package com.vireal.bot.handlers

import com.vireal.bot.service.BotService
import com.vireal.bot.utils.CallbackState
import com.vireal.bot.utils.mapWaitingStateToMCPType
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.*
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.message
import org.slf4j.LoggerFactory

object CallbackHandlers {
  private val logger = LoggerFactory.getLogger(this::class.java)

  suspend fun register(context: BehaviourContext, botService: BotService) = with(context) {

    onDataCallbackQuery { query ->
      val userId = query.from.id.chatId
      val data = query.data

      try {
        when {
          data == CallbackState.SAVE_NOTE.name -> {
            val state = MessageHandlers.getUserState(userId)
            val text = state?.lastMessage

            if (text != null) {
              val response = botService.createNote(userId, text)

              if (response.success) {
                answerCallbackQuery(query, "✅ Заметка сохранена!")
                query.message?.let {
                  editMessageText(
                    it.chat,
                    it.messageId,
                    "✅ Заметка сохранена!\nID: ${response.noteId}"
                  )
                }
              } else {
                answerCallbackQuery(query, "❌ Ошибка")
              }

              MessageHandlers.removeUserState(userId)
            }
          }

          data == CallbackState.SEARCH_TEXT.name -> {
            val state = MessageHandlers.getUserState(userId)
            val text = state?.lastMessage

            if (text != null) {
              val results = botService.searchNotes(userId, text)

              answerCallbackQuery(
                query,
                "Найдено ${results.totalFound} заметок"
              )

              query.message?.let {
                val resultText = if (results.notes.isEmpty()) {
                  "Ничего не найдено"
                } else {
                  "Найдено ${results.totalFound} заметок:\n" +
                    results.notes.take(3).joinToString("\n") { note ->
                      "• ${note.content.take(100)}"
                    }
                }

                editMessageText(it.chat, it.messageId, resultText)
              }

              MessageHandlers.removeUserState(userId)
            }
          }

          data == CallbackState.CONFIRM_ACTION.name -> {
            answerCallbackQuery(query, "✅ Действие подтверждено")
            val state = MessageHandlers.getUserState(userId)
            val text = state?.lastMessage
            val waitingState = state?.waitingFor
            if (waitingState == null || text == null) {
              query.message?.let {
                editMessageText(
                  it.chat,
                  it.messageId,
                  "❌ Ошибка: нет ожидаемого действия"
                )
              }
              return@onDataCallbackQuery
            }
            val mcpRequest = botService.mcpClient.createToolRequest(
              type = mapWaitingStateToMCPType(waitingState),
              userId = userId,
              question = text,
            )
            botService.mcpClient.executeTool(mcpRequest)

            query.message?.let {
              editMessageText(
                it.chat,
                it.messageId,
                "✅ Действие подтверждено"
              )
            }
          }

          data == CallbackState.DECLINE_ACTION.name -> {
            answerCallbackQuery(query, "❌ Действие отклонено")
            query.message?.let {
              editMessageText(
                it.chat,
                it.messageId,
                "❌ Действие отклонено"
              )
            }
            MessageHandlers.removeUserState(userId)
          }

          data == CallbackState.ASK_QUESTION.name -> {
            val state = MessageHandlers.getUserState(userId)
            val text = state?.lastMessage

            if (text != null) {
              answerCallbackQuery(query, "🤔 Поиск ответа...")

              query.message?.let { message ->
                editMessageText(
                  message.chat,
                  message.messageId,
                  "🤔 Поиск в базе знаний..."
                )

                try {
                  val mcpResult = botService.askQuestionWithKnowledgeBaseMCP(userId, text)

                  if (mcpResult.isError) {
                    editMessageText(
                      message.chat,
                      message.messageId,
                      "❌ Ошибка: ${mcpResult.content.firstOrNull()?.text ?: "Неизвестная ошибка"}"
                    )
                  } else {
                    val content = mcpResult.content.firstOrNull()
                    val answer = content?.text ?: "Не удалось получить ответ"
                    val metadata = content?.metadata

                    // Формируем расширенный ответ
                    val responseText = buildString {
                      append("❓ Ваш вопрос: $text\n\n")
                      append("💡 Ответ:\n$answer")

                      metadata?.let { meta ->
                        val sourcesCount = meta["sources_count"]?.toString()?.toIntOrNull()
                        val searchTime = meta["search_time_ms"]?.toString()?.toLongOrNull()

                        if (sourcesCount != null && sourcesCount > 0) {
                          append("\n\n📚 Использовано источников: $sourcesCount")
                          if (searchTime != null) {
                            append(" (поиск: ${searchTime}мс)")
                          }
                        }
                      }
                    }

                    editMessageText(message.chat, message.messageId, responseText)
                  }
                } catch (e: Exception) {
                  logger.error("Error processing question via callback", e)
                  editMessageText(
                    message.chat,
                    message.messageId,
                    "❌ Ошибка обработки вопроса"
                  )
                }
              }

              MessageHandlers.removeUserState(userId)
            }
          }

          data.startsWith("similar:") -> {
            val noteId = data.substringAfter("similar:")
            val similar = botService.findSimilarNotes(userId, noteId)

            query.message?.let {
              val text = if (similar.isEmpty()) {
                "Похожие заметки не найдены"
              } else {
                "🔗 Похожие заметки:\n" +
                  similar.take(3).joinToString("\n") { note ->
                    "• ${note.content.take(100)}"
                  }
              }

              send(it.chat, text)
            }

            answerCallbackQuery(query)
          }

          data.startsWith("tag:") -> {
            val tag = data.substringAfter("tag:")
            val notes = botService.getNotesByTag(userId, tag)

            query.message?.let {
              val text = "Заметки с тегом #$tag:\n" +
                notes.take(5).joinToString("\n") { note ->
                  "• ${note.content.take(100)}"
                }

              send(it.chat, text)
            }

            answerCallbackQuery(query)
          }

          data.startsWith("category:") -> {
            val category = data.substringAfter("category:")
            val notes = botService.getNotesByCategory(userId, category)

            query.message?.let {
              val text = "Заметки в категории $category:\n" +
                notes.take(5).joinToString("\n") { note ->
                  "• ${note.content.take(100)}"
                }

              send(it.chat, text)
            }

            answerCallbackQuery(query)
          }

          data.startsWith("confirm_delete:") -> {
            val noteId = data.substringAfter("confirm_delete:")
            val success = botService.deleteNote(noteId)

            if (success) {
              answerCallbackQuery(query, "✅ Заметка удалена")
              query.message?.let {
                editMessageText(
                  it.chat,
                  it.messageId,
                  "✅ Заметка удалена"
                )
              }
            } else {
              answerCallbackQuery(query, "❌ Ошибка удаления")
            }
          }

          data == CallbackState.CANCEL_DELETE.name -> {
            answerCallbackQuery(query, "Отменено")
            query.message?.let {
              deleteMessage(it.chat, it.messageId)
            }
          }

          data == CallbackState.CANCEL_ACTION.name -> {
            answerCallbackQuery(query, "Отменено")
            query.message?.let {
              deleteMessage(it.chat, it.messageId)
            }
            MessageHandlers.removeUserState(userId)
          }

          data.startsWith("more:") -> {
            val limit = data.substringAfter("more:").toIntOrNull() ?: 10
            val notes = botService.getUserNotes(userId, limit)

            query.message?.let {
              val text = "📚 Ваши заметки (${notes.size}):\n" +
                notes.joinToString("\n") { note ->
                  "• ${note.content.take(100)}"
                }

              editMessageText(it.chat, it.messageId, text)
            }

            answerCallbackQuery(query)
          }

          else -> {
            answerCallbackQuery(query, "Неизвестная команда")
          }
        }
      } catch (e: Exception) {
        logger.error("Error handling callback", e)
        answerCallbackQuery(query, "❌ Произошла ошибка")
      }
    }
  }
}
