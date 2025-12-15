package com.vireal.bot.handlers

import com.vireal.bot.utils.escapeMarkdownV2
import com.vireal.bot.service.BotService
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.*
import dev.inmo.tgbotapi.extensions.api.send.*
import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.replyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.message.MarkdownParseMode
import dev.inmo.tgbotapi.utils.*
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

object CommandHandlers {
  private val logger = LoggerFactory.getLogger(this::class.java)

  suspend fun register(context: BehaviourContext, botService: BotService) = with(context) {

    // Команда /start
    onCommand("start") { message ->
      val welcomeMessage = """
                🎉 *Добро пожаловать в Knowledge Base Bot\!*

                Я помогу вам управлять персональной базой знаний\.

                Отправь факт или вопрос в чат

                В ответ, бот предложит сохранить заметку или ответить на вопрос с контекстом из базы\.

                Или просто отправьте текст для добавления заметки\!
            """.trimIndent()

      send(
        message.chat,
        welcomeMessage,
        parseMode = MarkdownParseMode,
        replyMarkup = getMainKeyboard()
      )
    }

    // Команда /mcp - информация о MCP инструментах
    onCommand("mcp") { message ->
      val tempMsg = send(message.chat, "🔍 Получение информации о MCP инструментах...")

      try {
        val tools = botService.mcpClient.getAvailableTools()

        val mcpInfo = buildString {
          appendLine("🛠 **Доступные MCP инструменты:**")
          appendLine()

          tools.forEach { tool ->
            appendLine("**${tool.name}**")
            appendLine("📋 ${tool.description}")
            appendLine()
          }

          if (tools.isEmpty()) {
            appendLine("❌ Инструменты не найдены")
          }
        }.escapeMarkdownV2()

        editMessageText(
          message.chat,
          tempMsg.messageId,
          mcpInfo,
          parseMode = MarkdownParseMode
        )
      } catch (e: Exception) {
        logger.error("Error getting MCP tools info", e)
        editMessageText(
          message.chat,
          tempMsg.messageId,
          "❌ Ошибка получения информации о MCP инструментах"
        )
      }
    }

    // Команда /mcpquery - пример MCP запроса с детальной информацией
    onCommand("mcpquery") { message ->
      val args = message.content.text.substringAfter("/mcpquery").trim()

      if (args.isEmpty()) {
        send(
          message.chat, """
          📝 **Использование:** `/mcpquery ваш вопрос`

          **Пример:** `/mcpquery Как настроить Docker?`

          Эта команда покажет детальную информацию о MCP запросе, включая метаданные поиска.
        """.trimIndent(), parseMode = MarkdownParseMode
        )
        return@onCommand
      }

      val userId = message.chat.id.chatId
      val tempMsg = send(message.chat, "🤔 Выполнение MCP запроса...")

      try {
        val mcpResult = botService.askQuestionWithKnowledgeBaseMCP(userId, args)

        val responseText = buildString {
          appendLine("🔍 **MCP Query результат:**")
          appendLine()
          appendLine("**Вопрос:** $args")
          appendLine()

          if (mcpResult.isError) {
            appendLine("❌ **Ошибка:** ${mcpResult.content.firstOrNull()?.text}")
          } else {
            val content = mcpResult.content.firstOrNull()
            appendLine("💡 **Ответ:**")
            appendLine(content?.text ?: "Нет ответа")
            appendLine()

            content?.metadata?.let { metadata ->
              appendLine("📊 **Метаданные:**")
              metadata["sources_count"]?.toString()?.let {
                appendLine("• Источников найдено: $it")
              }
              metadata["search_time_ms"]?.toString()?.let {
                appendLine("• Время поиска: ${it}мс")
              }
              metadata["total_found"]?.toString()?.let {
                appendLine("• Всего найдено: $it")
              }
            }
          }
        }.escapeMarkdownV2()

        editMessageText(
          message.chat,
          tempMsg.messageId,
          responseText.escapeMarkdownV2(),
          parseMode = MarkdownParseMode
        )
      } catch (e: Exception) {
        logger.error("Error executing MCP query", e)
        editMessageText(
          message.chat,
          tempMsg.messageId,
          "❌ Ошибка выполнения MCP запроса"
        )
      }
    }

    // Команда /mcpraw - запрос без поиска в базе знаний
    onCommand("mcpraw") { message ->
      val args = message.content.text.substringAfter("/mcpraw").trim()

      if (args.isEmpty()) {
        send(
          message.chat, """
          📝 **Использование:** `/mcpraw ваш вопрос`

          **Пример:** `/mcpraw Объясни что такое REST API`

          Эта команда отправляет запрос напрямую к LLM без поиска в базе знаний.
        """.trimIndent(), parseMode = MarkdownParseMode
        )
        return@onCommand
      }

      val tempMsg = send(message.chat, "🤔 Обработка запроса без поиска...")

      try {
        val mcpResult = botService.askQuestionWithoutKnowledgeBaseMCP(args)

        val responseText = buildString {
          appendLine("🚀 **MCP Raw Query результат:**")
          appendLine()
          appendLine("**Вопрос:** $args")
          appendLine()

          if (mcpResult.isError) {
            appendLine("❌ **Ошибка:** ${mcpResult.content.firstOrNull()?.text}")
          } else {
            val content = mcpResult.content.firstOrNull()
            appendLine("💡 **Ответ:**")
            appendLine(content?.text ?: "Нет ответа")
            appendLine()

            content?.metadata?.let { metadata ->
              appendLine("📊 **Метаданные:**")
              metadata["context_provided"]?.toString()?.let {
                appendLine("• Контекст предоставлен: $it")
              }
              metadata["context_length"]?.toString()?.let {
                appendLine("• Длина контекста: $it символов")
              }
            }
          }
        }.escapeMarkdownV2()

        editMessageText(
          message.chat,
          tempMsg.messageId,
          responseText.escapeMarkdownV2(),
          parseMode = MarkdownParseMode
        )
      } catch (e: Exception) {
        logger.error("Error executing MCP raw query", e)
        editMessageText(
          message.chat,
          tempMsg.messageId,
          "❌ Ошибка выполнения запроса"
        )
      }
    }

    // Команда /help
    onCommand("help") { message ->
      val helpMessage = """
                📖 *Подробная справка*

                *Добавление заметок:*
                • `/add \[текст\]` \- Добавить с автотегами
                • Просто отправьте текст

                *Поиск и вопросы:*
                • `/search \[запрос\]` \- Векторный поиск
                • `/ask \[вопрос\]` \- Получить ответ из базы
                • `/similar \[id\]` \- Похожие заметки

                *Организация:*
                • `/tags` \- Все ваши теги
                • `/category \[название\]` \- По категории
                • `/mynotes \[число\]` \- Последние N заметок

                *Управление:*
                • `/delete \[id\]` \- Удалить заметку
                • `/stats` \- Статистика базы
                • `/export` \- Экспорт заметок
                • `/process` \- Обработать embeddings

                💡 *Совет:* Используйте \#хештеги для категоризации\!
            """.trimIndent()

      send(message.chat, helpMessage, parseMode = MarkdownParseMode)
    }

    // Команда /add
    onCommand("add") { message ->
      val userId = message.chat.id.chatId
      val text = message.content.text.substringAfter("/add").trim()

      if (text.isEmpty()) {
        send(message.chat, "Пожалуйста, укажите текст заметки после команды /add")
        return@onCommand
      }

      val tempMsg = send(message.chat, "⏳ Сохраняю заметку...")

      try {
        val response = botService.createNoteMCP(userId, text)

        if (!response.isError) {
          editMessageText(message.chat, tempMsg.messageId, "❌ Ошибка при сохранении заметки")
        } else {
          val successMsg = "✅ Заметка сохранена!"
          editMessageText(
            message.chat,
            tempMsg.messageId,
            successMsg,
            parseMode = MarkdownParseMode,
          )
        }
      } catch (e: Exception) {
        logger.error("Error adding note", e)
        editMessageText(
          message.chat,
          tempMsg.messageId,
          "❌ Произошла ошибка при сохранении"
        )
      }
    }

    // Команда /search
    onCommand("search") { message ->
      val userId = message.chat.id.chatId
      val query = message.content.text.substringAfter("/search").trim()

      if (query.isEmpty()) {
        send(message.chat, "Укажите поисковый запрос после /search")
        return@onCommand
      }

      val tempMsg = send(message.chat, "🔍 Ищу...")

      try {
        val results = botService.searchNotes(userId, query)

        if (results.notes.isEmpty()) {
          editMessageText(
            message.chat,
            tempMsg.messageId,
            "Не найдено заметок по запросу: *$query*",
            parseMode = MarkdownParseMode
          )
          return@onCommand
        }

        val resultText = buildString {
          appendLine("*Найдено ${results.totalFound} заметок:*")
          appendLine()

          results.notes.take(5).forEachIndexed { index, note ->
            appendLine("${index + 1}\\. ${note.content.take(100).escapeMarkdownV2()}")
            if (note.tags.isNotEmpty()) {
              appendLine("   🏷 ${note.tags.joinToString(", ") { it.escapeMarkdownV2() }}")
            }
            appendLine("   🆔 `${note.id}`")
            appendLine()
          }
        }.trimIndent()

        editMessageText(
          message.chat,
          tempMsg.messageId,
          resultText,
          parseMode = MarkdownParseMode
        )
      } catch (e: Exception) {
        logger.error("Error searching", e)
        editMessageText(
          message.chat,
          tempMsg.messageId,
          "❌ Ошибка при поиске"
        )
      }
    }

    // Команда /ask
    onCommand("ask") { message ->
      val userId = message.chat.id.chatId
      val question = message.content.text.substringAfter("/ask").trim()

      if (question.isEmpty()) {
        send(message.chat, "Задайте вопрос после команды /ask")
        return@onCommand
      }

      val tempMsg = send(message.chat, "🤔 Анализирую базу знаний...")

      try {
        val response = botService.askQuestionWithKnowledgeBaseMCP(userId, question)

        val answerText = buildString {
          appendLine("*Вопрос:* ${question.escapeMarkdownV2()}")
          appendLine()
          appendLine("*Ответ:*")

          if (response.isError) {
            appendLine(response.content.firstOrNull()?.text?.escapeMarkdownV2() ?: "Неизвестная ошибка")
          } else {
            appendLine(response.content.firstOrNull()?.text?.escapeMarkdownV2() ?: "Нет ответа")

            response.content.firstOrNull()?.metadata?.get("sources")?.jsonArray?.let { sources ->
              if (sources.isNotEmpty()) {
                appendLine()
                appendLine("*Источники:*")
                sources.forEach { source ->
                  appendLine("• ${source.jsonPrimitive.content.escapeMarkdownV2()}")
                }
              }
            }
          }
        }.trimIndent()

        editMessageText(
          message.chat,
          tempMsg.messageId,
          answerText,
          parseMode = MarkdownParseMode
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

    // Команда /mynotes
    onCommand("mynotes") { message ->
      val userId = message.chat.id.chatId
      val limitStr = message.content.text.substringAfter("/mynotes").trim()
      val limit = limitStr.toIntOrNull() ?: 5

      try {
        val notes = botService.getUserNotes(userId, limit)

        if (notes.isEmpty()) {
          send(message.chat, "У вас пока нет заметок. Используйте /add для добавления.")
          return@onCommand
        }

        val notesText = buildString {
          appendLine("*📚 Ваши последние $limit заметок:*")
          appendLine()

          notes.forEachIndexed { index, note ->
            appendLine("${index + 1}\\. ${note.content.take(150).escapeMarkdownV2()}")
            appendLine("   📅 ${note.createdAt.take(10)}")
            appendLine("   🆔 `${note.id}`")
            appendLine()
          }
        }.trimIndent()

        send(
          message.chat,
          notesText,
          parseMode = MarkdownParseMode,
          replyMarkup = inlineKeyboard {
            row {
              dataButton("📥 Показать еще", "more:${limit + 5}")
            }
          }
        )
      } catch (e: Exception) {
        logger.error("Error getting notes", e)
        send(message.chat, "❌ Ошибка получения заметок")
      }
    }

    // Команда /tags
    onCommand("tags") { message ->
      val userId = message.chat.id.chatId

      try {
        val tags = botService.getUserTags(userId)

        if (tags.isEmpty()) {
          send(message.chat, "У вас пока нет тегов")
          return@onCommand
        }

        val tagsText = buildString {
          appendLine("*🏷 Ваши теги:*")
          appendLine()
          tags.forEach { tag ->
            appendLine("#${tag.escapeMarkdownV2()}")
          }
        }.trimIndent()

        val keyboard = inlineKeyboard {
          tags.chunked(3).forEach { row ->
            row {
              row.forEach { tag ->
                dataButton("#$tag", "tag:$tag")
              }
            }
          }
        }

        send(
          message.chat,
          tagsText,
          parseMode = MarkdownParseMode,
          replyMarkup = keyboard
        )
      } catch (e: Exception) {
        logger.error("Error getting tags", e)
        send(message.chat, "❌ Ошибка получения тегов")
      }
    }

    // Команда /category
    onCommand("category") { message ->
      val userId = message.chat.id.chatId
      val category = message.content.text.substringAfter("/category").trim()

      try {
        if (category.isEmpty()) {
          val stats = botService.getCategoryStats(userId)

          if (stats.isEmpty()) {
            send(message.chat, "У вас пока нет категорий")
            return@onCommand
          }

          val statsText = buildString {
            appendLine("*📁 Ваши категории:*")
            appendLine()
            stats.forEach { (cat, count) ->
              appendLine("• ${cat.escapeMarkdownV2()}: $count заметок")
            }
          }.trimIndent()

          val keyboard = inlineKeyboard {
            stats.keys.chunked(2).forEach { rowCats ->
              row {
                rowCats.forEach { cat ->
                  dataButton("📁 $cat", "category:$cat")
                }
              }
            }
          }

          send(
            message.chat,
            statsText,
            parseMode = MarkdownParseMode,
            replyMarkup = keyboard
          )
        } else {
          val notes = botService.getNotesByCategory(userId, category)

          if (notes.isEmpty()) {
            send(message.chat, "Нет заметок в категории: $category")
            return@onCommand
          }

          val notesText = buildString {
            appendLine("*📁 Категория: ${category.escapeMarkdownV2()}*")
            appendLine("Найдено ${notes.size} заметок")
            appendLine()

            notes.take(10).forEachIndexed { index, note ->
              appendLine("${index + 1}\\. ${note.content.take(100).escapeMarkdownV2()}")
              appendLine()
            }
          }.trimIndent()

          send(message.chat, notesText, parseMode = MarkdownParseMode)
        }
      } catch (e: Exception) {
        logger.error("Error handling category", e)
        send(message.chat, "❌ Произошла ошибка")
      }
    }

    // Команда /stats
    onCommand("stats") { message ->
      val userId = message.chat.id.chatId

      try {
        val notesCount = botService.getNotesCount(userId)
        val tags = botService.getUserTags(userId)
        val categories = botService.getCategoryStats(userId)

        val statsText = buildString {
          appendLine("*📊 Статистика вашей базы знаний:*")
          appendLine()
          appendLine("📝 Всего заметок: $notesCount")
          appendLine("🏷 Уникальных тегов: ${tags.size}")
          appendLine("📁 Категорий: ${categories.size}")

          if (categories.isNotEmpty()) {
            appendLine()
            appendLine("*Топ категорий:*")
            categories.entries
              .sortedByDescending { it.value }
              .take(5)
              .forEach { (cat, count) ->
                appendLine("• ${cat.escapeMarkdownV2()}: $count")
              }
          }

          if (tags.isNotEmpty()) {
            appendLine()
            appendLine("*Популярные теги:*")
            appendLine(tags.take(10).joinToString(", ") { "#${it.escapeMarkdownV2()}" })
          }
        }.trimIndent()

        send(message.chat, statsText, parseMode = MarkdownParseMode)
      } catch (e: Exception) {
        logger.error("Error getting stats", e)
        send(message.chat, "❌ Ошибка получения статистики")
      }
    }

    // Команда /similar
    onCommand("similar") { message ->
      val userId = message.chat.id.chatId
      val noteId = message.content.text.substringAfter("/similar").trim()

      if (noteId.isEmpty()) {
        send(message.chat, "Укажите ID заметки: /similar [id]")
        return@onCommand
      }

      val tempMsg = send(message.chat, "🔍 Ищу похожие заметки...")

      try {
        val similar = botService.findSimilarNotes(userId, noteId)

        if (similar.isEmpty()) {
          editMessageText(
            message.chat,
            tempMsg.messageId,
            "Похожие заметки не найдены"
          )
          return@onCommand
        }

        val similarText = buildString {
          appendLine("*🔗 Похожие заметки:*")
          appendLine()

          similar.forEachIndexed { index, note ->
            appendLine("${index + 1}\\. ${note.content.take(150).escapeMarkdownV2()}")
            appendLine("   🆔 `${note.id}`")
            appendLine()
          }
        }.trimIndent()

        editMessageText(
          message.chat,
          tempMsg.messageId,
          similarText,
          parseMode = MarkdownParseMode
        )
      } catch (e: Exception) {
        logger.error("Error finding similar", e)
        editMessageText(
          message.chat,
          tempMsg.messageId,
          "❌ Ошибка поиска похожих заметок"
        )
      }
    }

    // Команда /delete
    onCommand("delete") { message ->
      val noteId = message.content.text.substringAfter("/delete").trim()

      if (noteId.isEmpty()) {
        send(message.chat, "Укажите ID заметки: /delete [id]")
        return@onCommand
      }

      send(
        message.chat,
        "Вы уверены, что хотите удалить заметку?",
        replyMarkup = inlineKeyboard {
          row {
            dataButton("✅ Да", "confirm_delete:$noteId")
            dataButton("❌ Нет", "cancel_delete")
          }
        }
      )
    }

    // Команда /export
    onCommand("export") { message ->
      val userId = message.chat.id.chatId

      val tempMsg = send(message.chat, "📥 Экспортирую вашу базу знаний...")

      try {
        val notes = botService.getUserNotes(userId, 1000)

        if (notes.isEmpty()) {
          editMessageText(
            message.chat,
            tempMsg.messageId,
            "У вас нет заметок для экспорта"
          )
          return@onCommand
        }

        val export = buildString {
          appendLine("=== ЭКСПОРТ БАЗЫ ЗНАНИЙ ===")
          appendLine("Дата: ${LocalDateTime.now()}")
          appendLine("Всего заметок: ${notes.size}")
          appendLine("=".repeat(30))
          appendLine()

          notes.forEach { note ->
            appendLine("ID: ${note.id}")
            appendLine("Дата: ${note.createdAt}")
            note.category?.let { appendLine("Категория: $it") }
            if (note.tags.isNotEmpty()) {
              appendLine("Теги: ${note.tags.joinToString(", ")}")
            }
            appendLine("Содержание:")
            appendLine(note.content)
            appendLine("-".repeat(30))
            appendLine()
          }
        }

        // Отправляем как документ
        val fileName = "knowledge_base_export_${System.currentTimeMillis()}.txt"
        val packet = ByteReadPacket(export.toByteArray())

        sendDocument(
          message.chat,
          InputFile.fromInput(fileName) { packet },
          text = "✅ Экспорт готов! Всего заметок: ${notes.size}"
        )

        deleteMessage(message.chat, tempMsg.messageId)
      } catch (e: Exception) {
        logger.error("Error exporting", e)
        editMessageText(
          message.chat,
          tempMsg.messageId,
          "❌ Ошибка экспорта"
        )
      }
    }

    // Команда /process - обработка embeddings
    onCommand("process") { message ->
      val userId = message.chat.id.chatId

      val tempMsg = send(message.chat, "⚙️ Обрабатываю заметки без embeddings...")

      try {
        val processed = botService.processEmbeddings(userId)

        editMessageText(
          message.chat,
          tempMsg.messageId,
          "✅ Обработано заметок: $processed"
        )
      } catch (e: Exception) {
        logger.error("Error processing embeddings", e)
        editMessageText(
          message.chat,
          tempMsg.messageId,
          "❌ Ошибка обработки embeddings"
        )
      }
    }
  }

  private fun getMainKeyboard() = replyKeyboard(
    resizeKeyboard = true,
    oneTimeKeyboard = false
  ) {
    row {
      simpleButton("📝 Добавить заметку")
      simpleButton("❓ Задать вопрос")
    }
  }
}
