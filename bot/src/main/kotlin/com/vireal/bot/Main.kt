package com.vireal.bot

import com.vireal.bot.api.ApiClient
import com.vireal.bot.config.BotConfig
import com.vireal.bot.handlers.MessageHandlers
import com.vireal.bot.service.BotService
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Main")

suspend fun main() {
  val config = BotConfig.load()
  val apiClient = ApiClient(config.apiBaseUrl)
  val botService = BotService(apiClient)

  val bot = telegramBot(config.telegramToken)

  bot.buildBehaviourWithLongPolling {
    logger.info("Bot started: ${getMe()}")

    // Регистрируем обработчики команд
    CommandHandlers.register(this, botService)

    // Регистрируем обработчики callback queries
    CallbackHandlers.register(this, botService)

    // Регистрируем обработчики обычных сообщений
    MessageHandlers.register(this, botService)

  }.join()
}
