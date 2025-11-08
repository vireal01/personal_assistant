package com.vireal.bot.handlers

import com.vireal.bot.service.BotService
import dev.inmo.kslog.common.d
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.logger
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDocument
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMediaContent
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMediaGroup
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.extensions.utils.ifFromChannel
import dev.inmo.tgbotapi.extensions.utils.ifFromSupergroup
import dev.inmo.tgbotapi.extensions.utils.ifMessageContent
import dev.inmo.tgbotapi.extensions.utils.mediaContentOrNull
import dev.inmo.tgbotapi.extensions.utils.mediaGroupContentOrNull
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object MessageHandlers {
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

  private data class ForwardBatch(
    val messages: MutableList<CommonMessage<TextContent>>
  )

  private val forwardBatches = ConcurrentHashMap<Long, ForwardBatch>()
  private val batchTimers = ConcurrentHashMap<Long, Job>()
  private const val FORWARD_BATCH_DELAY = 1000L // 1 second

  suspend fun register(context: BehaviourContext, botService: BotService) = with(context) {

    onMediaGroup {
      println("Received media group: ${it.mediaGroupContentOrNull()}")
      println("mainContent.text${it.mainContent.text}")
      println("${it.mainContent.logger}")
      println("mainContent.textSources: ${it.mainContent.textSources.joinToString("\n")}")
      println(it.mainContent.ifMessageContent { messageContent ->
       println("ifMessageContent $messageContent")
      })
    }

    onMediaContent {
      println("Received media content: ${it.content}")
      logger.d("logger: Received media content: ${it.content}" )
      println("${it.content.logger}")
    }

    onDocument {
      println("Received document: ${it.content}")
    }

    // Обработка обычных текстовых сообщений
    onText { message ->
      val userId = message.chat.id.chatId
      println(message.content)
      println("Received text message from user $userId: ${message.content.text}")
      println("is mediaGroupContent: ${message.content.mediaGroupContentOrNull().toString()}")
      println("is mediaContent: ${message.content.mediaContentOrNull().toString()}")
      val text = message.content.text

      // Обработка пересланных сообщений
      if (message.forwardInfo != null) {

        message.forwardInfo?.ifFromChannel {
          println("https://t.me/${it.channelChat.id}/${it.messageId}")
        }

        message.forwardInfo?.ifFromSupergroup {
          println("https://t.me/${it.group.id}/")
        }

        batchTimers[userId]?.cancel()

        val batch = forwardBatches.getOrPut(userId) { ForwardBatch(mutableListOf()) }
        batch.messages.add(message)

        batchTimers[userId] = launch {
          delay(FORWARD_BATCH_DELAY)
          forwardBatches.remove(userId)?.let {
            processForwardBatch(userId, it.messages)
          }
          batchTimers.remove(userId)
        }
        return@onText
      }

      // Игнорируем команды
      if (text.startsWith("/")) return@onText

      // Обработка кнопок клавиатуры
      when (text) {
        "📝 Добавить заметку" -> {
          send(message.chat, "Отправьте текст заметки:")
          userStates[userId] = UserState(waitingFor = WaitingState.NOTE_TEXT)
        }

//        "🔍 Поиск" -> {
//          send(message.chat, "Введите поисковый запрос:")
//          userStates[userId] = UserState(waitingFor = WaitingState.SEARCH_QUERY)
//        }

        "❓ Задать вопрос" -> {
          send(message.chat, "Задайте ваш вопрос:")
          userStates[userId] = UserState(waitingFor = WaitingState.QUESTION)
        }

//        "📚 Мои заметки" -> {
//          handleMyNotes(message, botService)
//        }
//
//        "🏷 Теги" -> {
//          handleTags(message, botService)
//        }
//
//        "📊 Статистика" -> {
//          handleStats(message, botService)
//        }

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
                replyMarkup = createActionKeyboard()
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
            appendLine("${i + 1}. ${note.content.take(100)}")
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
            appendLine("${i + 1}. ${note.content.take(100)}")
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
  fun setUserState(userId: Long, state: UserState) {
    userStates[userId] = state
  }
}

private fun createActionKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
  row {
    dataButton("📝 Сохранить заметку", "save_note")
    dataButton("❓ Задать вопрос", "ask_question")
  }
  row {
    dataButton("❌ Отмена", "cancel")
  }
}

private suspend fun BehaviourContext.processForwardBatch(
  userId: Long,
  messages: List<CommonMessage<TextContent>>
) {
  if (messages.isEmpty()) return
  val firstMessage = messages.first()
  val chat = firstMessage.chat

  try {
    val mergedText = messages.joinToString("\n\n") { it.content.text }
    MessageHandlers.setUserState(userId, MessageHandlers.UserState(lastMessage = mergedText))

    val messageText = if (messages.size == 1) {
      "Что сделать с пересланным сообщением?"
    } else {
      "Получена пачка из ${messages.size} сообщений. Что с ней сделать?"
    }

    send(
      chat,
      messageText,
      replyMarkup = createActionKeyboard()
    )
  } catch (e: Exception) {
    logger.error("Error processing forward batch for user $userId", e)
    send(chat, "❌ Произошла ошибка при обработке пересланных сообщений.")
  }
}
