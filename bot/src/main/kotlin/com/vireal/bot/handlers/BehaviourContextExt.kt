package com.vireal.bot.handlers

import com.vireal.bot.service.BotService
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.logger
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.content.TextedContent


internal suspend fun BehaviourContext.handleQuestionKnowledgeBase(
  message: CommonMessage<TextedContent>,
  question: String,
  botService: BotService
) {
  val userId = message.chat.id.chatId
  val tempMsg = send(message.chat, "🤔 Анализирую...")

  try {
    val response = botService.askQuestionWithKnowledgeBaseContext(userId, question)
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


internal suspend fun BehaviourContext.handleQuestionLLM(
  message: CommonMessage<TextedContent>,
  question: String,
  context: String,
  botService: BotService
) {
  val userId = message.chat.id.chatId
  val tempMsg = send(message.chat, "🤔 Анализирую...")

  try {
    val response = botService.askQuestionWithNoKnowledgeBaseContext(
      userId = userId,
      question = question,
      context = context
    )
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
