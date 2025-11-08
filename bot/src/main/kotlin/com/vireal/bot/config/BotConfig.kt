package com.vireal.bot.config

import io.github.cdimascio.dotenv.dotenv

data class BotConfig(
  val telegramToken: String,
  val apiBaseUrl: String,
  val openAIApiKey: String,
  val openAIModel: String,
  val maxNotesPerRequest: Int = 10,
  val enableDebugLogging: Boolean = false
) {
  companion object {
    fun load(): BotConfig {
      val dotenv = dotenv {
        ignoreIfMissing = true
        systemProperties = true
      }

      return BotConfig(
        telegramToken = dotenv["TELEGRAM_BOT_TOKEN"]
          ?: throw IllegalStateException("TELEGRAM_BOT_TOKEN not found"),

        apiBaseUrl = dotenv["API_BASE_URL"]
          ?: "http://api:8080",

        openAIApiKey = dotenv["OPENAI_API_KEY"]
          ?: "",

        openAIModel = if (dotenv["USE_NEW_OPENAI_API"]?.toBoolean() == true) {
          dotenv["OPENAI_RESPONSE_MODEL"] ?: "gpt-4.1"
        } else {
          dotenv["OPENAI_CHAT_MODEL"] ?: "gpt-3.5-turbo"
        },

        maxNotesPerRequest = dotenv["MAX_NOTES_PER_REQUEST"]?.toIntOrNull() ?: 10,

        enableDebugLogging = dotenv["DEBUG_LOGGING"]?.toBoolean() ?: false
      )
    }
  }
}
