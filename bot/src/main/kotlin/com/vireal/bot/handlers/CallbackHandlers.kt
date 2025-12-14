package com.vireal.bot.handlers

import com.vireal.bot.handlers.MessageHandlers.handleSingleMessage
import com.vireal.bot.service.BotService
import com.vireal.bot.utils.BotWaitingState
import com.vireal.bot.utils.CallbackState
import com.vireal.bot.utils.mapWaitingStateToMCPType
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.*
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.message
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import org.slf4j.LoggerFactory

object CallbackHandlers {
  private val logger = LoggerFactory.getLogger(this::class.java)

  suspend fun register(context: BehaviourContext, botService: BotService) = with(context) {

    onDataCallbackQuery { query ->
      val userId = query.from.id.chatId
      val data = query.data
      val state = MessageHandlers.getUserState(userId)
      val lastMessageText = state?.lastMessage

      try {
        when {
          data == CallbackState.SAVE_NOTE.name -> {

            println("DEBUG: SAVE_NOTE callback received for user $userId with last message: $lastMessageText")
            if (lastMessageText != null) {
              val response = botService.createNoteMCP(userId, lastMessageText)
              if (response.isError) {
                query.message?.let {
                  editMessageText(
                    it.chat,
                    it.messageId,
                    "❌ Ошибка. Заметка не была сохранена"
                  )
                }
              } else {
                answerCallbackQuery(query, "✅ Заметка сохранена!")
                query.message?.let {
                  editMessageText(
                    it.chat,
                    it.messageId,
                    "✅ Заметка сохранена!"
                  )
                }
              }

              MessageHandlers.removeUserState(userId)
            }
          }

          data == CallbackState.SEARCH_TEXT.name -> {
            if (lastMessageText != null) {
              val results = botService.searchNotes(userId, lastMessageText)

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
            val waitingState = state?.waitingFor
            showProgressMessage(query, waitingState = waitingState)
            if (waitingState == null || lastMessageText == null) {
              query.message?.let {
                editMessageText(
                  it.chat,
                  it.messageId,
                  "❌ Ошибка: нет ожидаемого действия"
                )
              }
              MessageHandlers.removeUserState(userId)
              return@onDataCallbackQuery
            }
            val result = botService.executeConfirmedAction(
              type = mapWaitingStateToMCPType(waitingState),
              userId = userId,
              text = lastMessageText
            )
            query.message?.let {
              editMessageText(
                it.chat,
                it.messageId,
                result.content.firstOrNull()?.text ?: "❌ Ошибка получения ответа"
              )
            }
            MessageHandlers.removeUserState(userId)
          }

          data == CallbackState.DECLINE_ACTION.name -> {
            answerCallbackQuery(query, "❌ Действие отклонено")
            query.message?.let {
              deleteMessage(
                it.chat,
                it.messageId,
              )
            }
            val currentState: MessageHandlers.UserState = MessageHandlers.getUserState(userId)
              ?: throw IllegalStateException("No user state found for user $userId on DECLINE_ACTION")
            MessageHandlers.setUserState(userId, currentState.copy(waitingFor = BotWaitingState.UNSPECIFIED_YET))
            val text = lastMessageText ?: ""
            val chat = query.message?.chat
            if (chat == null) {
              answerCallbackQuery(query, "❌ Внутренняя ошибка: нет чата")
              return@onDataCallbackQuery
            }
            context.handleSingleMessage(
              text = text,
              chat = chat,
              botService = botService
            )
          }

          data == CallbackState.ASK_QUESTION.name -> {
            if (lastMessageText != null) {
              answerCallbackQuery(query, "🤔 Поиск ответа...")

              query.message?.let { message ->
                editMessageText(
                  message.chat,
                  message.messageId,
                  "🤔 Поиск в базе знаний..."
                )

                try {
                  val mcpResult = botService.askQuestionWithKnowledgeBaseMCP(userId, lastMessageText)
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
                      append("❓ Ваш вопрос: $lastMessageText\n\n")
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

  private suspend fun BehaviourContext.showProgressMessage(query: DataCallbackQuery, waitingState: BotWaitingState?) {
    val message = when (waitingState) {
      BotWaitingState.KNOWLEDGE_BASE_QUERY -> "🤔 Поиск в базе знаний..."
      BotWaitingState.NOTE_SAVING -> "💾 Сохранение заметки..."
      BotWaitingState.SEARCH_NOTES -> "🔍 Поиск заметок..."
      else -> "⏳ Пожалуйста, подождите..."
    }
    query.message?.let {
      editMessageText(
        it.chat,
        it.messageId,
        message
      )
    }
  }
}
