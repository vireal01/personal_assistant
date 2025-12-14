package com.vireal.bot.handlers

import com.vireal.bot.service.BotService
import com.vireal.bot.utils.BotWaitingState
import com.vireal.bot.utils.CallbackState
import com.vireal.shared.models.MCPType
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.logger
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDocument
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onTextedMediaContent
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.text
import dev.inmo.tgbotapi.extensions.utils.ifFromChannel
import dev.inmo.tgbotapi.extensions.utils.textLinkTextSourceOrNull
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.uRLTextSourceOrNull
import dev.inmo.tgbotapi.types.ReplyInfo
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextedContent
import dev.inmo.tgbotapi.types.message.textsources.link
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

object MessageHandlers {
  private val userStates = mutableMapOf<Long, UserState>()

  data class UserState(
    var lastMessage: String? = null,
    var waitingFor: BotWaitingState? = BotWaitingState.UNSPECIFIED_YET
  )

  private data class ForwardBatch(
    val messages: MutableList<CommonMessage<TextedContent>>
  )

  private val forwardBatches = ConcurrentHashMap<Long, ForwardBatch>()
  private val batchTimers = ConcurrentHashMap<Long, Job>()
  private const val FORWARD_BATCH_DELAY = 1000L // 1 second

  suspend fun register(context: BehaviourContext, botService: BotService) = with(context) {

    // Text with any media
    onTextedMediaContent { message ->
      when {
        isReplyProcessed(message, botService) -> return@onTextedMediaContent
        isHandledBatchOfForwardedMessages(message) -> return@onTextedMediaContent
        else -> handleSingleMessage(message = message, botService = botService)
      }
    }

    onDocument {
      println("Received document: ${it.content}")
    }

    // Plain text
    onText { message ->
      when {
        isReplyProcessed(message, botService) -> return@onText
        isHandledBatchOfForwardedMessages(message) -> return@onText
        message.content.text.startsWith("/") -> return@onText
        else -> handleSingleMessage(message = message, botService = botService)
      }
    }
  }

  suspend fun BehaviourContext.handleSingleMessage(
    message: CommonMessage<TextedContent>,
    botService: BotService
  ) {
    val userId = message.chat.id.chatId
    when (val text = message.content.text) {
      "📝 Добавить заметку" -> {
        send(message.chat, "Отправьте текст заметки:")
        userStates[userId] = UserState(waitingFor = BotWaitingState.NOTE_SAVING)
      }

//        "🔍 Поиск" -> {
//          send(message.chat, "Введите поисковый запрос:")
//          userStates[userId] = UserState(waitingFor = WaitingState.SEARCH_QUERY)
//        }

      "❓ Задать вопрос" -> {
        send(message.chat, "Задайте ваш вопрос:")
        userStates[userId] = UserState(waitingFor = BotWaitingState.KNOWLEDGE_BASE_QUERY)
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
        val formatedText = message.content.processTextAndTextSources()
        val state = userStates[userId]

        when (state?.waitingFor) {

          BotWaitingState.NOTE_SAVING -> {
            handleAddNote(message, formatedText, botService)
            userStates.remove(userId)
          }

          BotWaitingState.SEARCH_NOTES -> {
            handleSearch(message, formatedText, botService)
            userStates.remove(userId)
          }

          BotWaitingState.KNOWLEDGE_BASE_QUERY -> {
            handleQuestionKnowledgeBase(message, formatedText, botService)
            userStates.remove(userId)
          }

          BotWaitingState.UNSPECIFIED_YET -> {
            userStates[userId] = UserState(lastMessage = text)

            send(
              message.chat,
              "Что сделать с этим текстом?",
              replyMarkup = createChooseActionKeyboard()
            )
          }

          BotWaitingState.SET_REMINDER_TIME -> {
            send(
              message.chat,
              "Бот пока не поддерживает установку напоминаний через текст. Пожалуйста, выберите другое действие.",
              replyMarkup = createChooseActionKeyboard()
            )
          }

          null -> {
            handleDecideToolToUse(message, formatedText, botService)
          }
        }
      }
    }
  }

  fun BehaviourContext.isHandledBatchOfForwardedMessages(message: CommonMessage<TextedContent>): Boolean {
    val userId = message.chat.id.chatId
    if (message.forwardInfo != null) {

      message.forwardInfo?.ifFromChannel {
        val link = "https://t.me/${it.channelChat.username?.usernameWithoutAt}/${it.messageId}"
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
      return true
    }
    return false
  }

  private suspend fun BehaviourContext.handleAddNote(
    message: CommonMessage<TextedContent>,
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
    message: CommonMessage<TextedContent>,
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

  private suspend fun BehaviourContext.handleDecideToolToUse(
    message: CommonMessage<TextedContent>,
    formatedText: String,
    botService: BotService,
  ) {
    val tempMsg = send(message.chat, "🤖 Определяем, что делать с вашим сообщением...")

    try {
      val result = botService.decideMCPToolToUse(formatedText)
      val userId = message.chat.id.chatId

      when (result.type) {
        MCPType.UNCATEGORIZED -> {
          val resultText =
            "🤖 Мы не смогли автоматически определить, что с этим сделать. Пожалуйста, выберите действие ниже."
          editMessageText(
            chat = message.chat,
            messageId = tempMsg.messageId,
            text = resultText,
            replyMarkup = createChooseActionKeyboard()
          )
        }

        MCPType.KNOWLEDGE_BASE_QUERY -> {
          val resultText = "🤔Сделать запрос к базе знаний по вашему сообщению?"
          userStates[userId] = UserState(lastMessage = formatedText, waitingFor = BotWaitingState.KNOWLEDGE_BASE_QUERY)
          editMessageText(
            chat = message.chat,
            messageId = tempMsg.messageId,
            text = resultText,
            replyMarkup = createConfirmActionCategoryChooseKeyboard()
          )
        }

        MCPType.NOTE_SAVING -> {
          val resultText = "📝Сохранить ваше сообщение как заметку?"
          userStates[userId] = UserState(lastMessage = formatedText, waitingFor = BotWaitingState.NOTE_SAVING)
          editMessageText(
            chat = message.chat,
            messageId = tempMsg.messageId,
            text = resultText,
            replyMarkup = createConfirmActionCategoryChooseKeyboard()
          )
        }

        MCPType.REMINDER_CREATION -> {
          val resultText = "⏲️Вы хотите установить напоминание?"
          userStates[userId] = UserState(lastMessage = formatedText, waitingFor = BotWaitingState.SET_REMINDER_TIME)
          editMessageText(
            chat = message.chat,
            messageId = tempMsg.messageId,
            text = resultText,
            replyMarkup = createConfirmActionCategoryChooseKeyboard()
          )
        }
      }

    } catch (e: Exception) {
      logger.error("Error MCP deciding", e)
      editMessageText(message.chat, tempMsg.messageId, "❌ Ошибка обработки")
    }
  }

  fun getUserState(userId: Long): UserState? = userStates[userId]
  fun removeUserState(userId: Long) = userStates.remove(userId)
  fun setUserState(userId: Long, state: UserState) {
    userStates[userId] = state
  }
}


private fun createChooseActionKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
  row {
    dataButton("📝 Сохранить заметку", CallbackState.SAVE_NOTE.name)
    dataButton("❓ Задать вопрос", CallbackState.ASK_QUESTION.name)
  }
  row {
    dataButton("❌ Отмена", CallbackState.CANCEL_ACTION.name)
  }
}

private fun createConfirmActionCategoryChooseKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
  row {
    dataButton(text = "✅Да", data = CallbackState.CONFIRM_ACTION.name)
    dataButton(text = "👎Нет", data = CallbackState.DECLINE_ACTION.name)
  }
}

private suspend fun BehaviourContext.processForwardBatch(
  userId: Long,
  messages: List<CommonMessage<TextedContent>>
) {
  if (messages.isEmpty()) return
  val firstMessage = messages.first()
  val chat = firstMessage.chat

  try {
    val mergedText = messages
      .sortedBy { it.date }
      .joinToString("\n\n---\n\n") { it.content.processTextAndTextSources() }
    MessageHandlers.setUserState(userId, MessageHandlers.UserState(lastMessage = mergedText))

    val messageText = if (messages.size == 1) {
      "Что сделать с пересланным сообщением?"
    } else {
      "Получена пачка из ${messages.size} сообщений. Что с ней сделать?"
    }

    send(
      chat,
      messageText,
      replyMarkup = createChooseActionKeyboard()
    )
  } catch (e: Exception) {
    logger.error(e)
    send(chat, "❌ Произошла ошибка при обработке пересланных сообщений.")
  }
}

private fun TextedContent.processTextAndTextSources(): String {
  val text = this.text
  val textSources = this.textSources
  val builder = StringBuilder()

  if (!text.isNullOrBlank()) {
    builder.append(text)
  }

  textSources.forEach { source ->
    source.uRLTextSourceOrNull()?.let { urlSource ->
      builder.append("\n")
      builder.append(link(urlSource.source, urlSource.asText).markdownV2)
    }
    source.textLinkTextSourceOrNull()?.let { linkSource ->
      builder.append("\n")
      builder.append(link(text = linkSource.source, url = linkSource.url).markdownV2)
    }
  }

  return builder.toString()
}


private suspend fun BehaviourContext.isReplyProcessed(
  message: CommonMessage<TextedContent>,
  botService: BotService,
): Boolean {
  if (message.replyInfo == null) return false
  message.replyInfo

  val internalMessage = message.replyInfo as ReplyInfo.Internal
  val messageText = internalMessage.message.text

  if (messageText.isNullOrBlank()) return false

  handleQuestionLLM(
    message = message,
    question = message.content.text.toString(),
    context = messageText,
    botService = botService,
  )
  return true
}
