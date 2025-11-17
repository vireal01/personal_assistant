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


/**
 * Обработка вопроса с поиском в базе знаний через MCP
 */
internal suspend fun BehaviourContext.handleQuestionKnowledgeBase(
  message: CommonMessage<TextedContent>,
  question: String,
  botService: BotService
) {
  val userId = message.chat.id.chatId
  val tempMsg = send(message.chat, "🤔 Поиск в базе знаний...")

  try {
    val mcpResult = botService.askQuestionWithKnowledgeBaseMCP(userId, question)

    if (mcpResult.isError) {
      editMessageText(
        message.chat,
        tempMsg.messageId,
        "❌ Ошибка: ${mcpResult.content.firstOrNull()?.text ?: "Неизвестная ошибка"}"
      )
      return
    }

    val content = mcpResult.content.firstOrNull()
    val answer = content?.text ?: "Не удалось получить ответ"
    val metadata = content?.metadata

    // Формируем расширенный ответ с метаданными
    val responseText = buildString {
      append(answer)

      metadata?.let { meta ->
        val sourcesCount = meta["sources_count"]?.toString()?.toIntOrNull()
        val searchTime = meta["search_time_ms"]?.toString()?.toLongOrNull()
        val totalFound = meta["total_found"]?.toString()?.toIntOrNull()

        if (sourcesCount != null || searchTime != null) {
          append("\n\n---")
          if (sourcesCount != null && sourcesCount > 0) {
            append("\n📚 Найдено источников: $sourcesCount")
          }
          if (totalFound != null && totalFound > sourcesCount ?: 0) {
            append(" (всего: $totalFound)")
          }
          if (searchTime != null) {
            append("\n⏱ Время поиска: ${searchTime}мс")
          }
        }
      }
    }

    editMessageText(message.chat, tempMsg.messageId, responseText)
  } catch (e: Exception) {
    logger.error("Error processing question with MCP", e)
    editMessageText(
      message.chat,
      tempMsg.messageId,
      "❌ Ошибка обработки вопроса"
    )
  }
}

/**
 * Обработка вопроса без поиска в базе знаний через MCP
 */
internal suspend fun BehaviourContext.handleQuestionLLM(
  message: CommonMessage<TextedContent>,
  question: String,
  context: String,
  botService: BotService
) {
  val tempMsg = send(message.chat, "🤔 Обрабатываю запрос...")

  try {
    val mcpResult = botService.askQuestionWithoutKnowledgeBaseMCP(question, context)

    if (mcpResult.isError) {
      editMessageText(
        message.chat,
        tempMsg.messageId,
        "❌ Ошибка: ${mcpResult.content.firstOrNull()?.text ?: "Неизвестная ошибка"}"
      )
      return
    }

    val content = mcpResult.content.firstOrNull()
    val answer = content?.text ?: "Не удалось получить ответ"
    val metadata = content?.metadata

    // Формируем ответ с информацией о контексте
    val responseText = buildString {
      append(answer)

      metadata?.let { meta ->
        val contextProvided = meta["context_provided"]?.toString()?.toBoolean()
        val contextLength = meta["context_length"]?.toString()?.toIntOrNull()

        if (contextProvided == true && contextLength != null && contextLength > 0) {
          append("\n\n---")
          append("\n📄 Использован контекст: ${contextLength} символов")
        }
      }
    }

    editMessageText(message.chat, tempMsg.messageId, responseText)
  } catch (e: Exception) {
    logger.error("Error processing question without knowledge base", e)
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
