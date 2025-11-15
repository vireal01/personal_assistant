package com.vireal.api.services

enum class LlmServiceMode {
  KNOWLEDGE_BASED,
  GENERATIVE_AI,
}


fun buildPrompt(
  userQuestion: String,
  context: String,
  mode: LlmServiceMode
): String {
  return when (mode) {
    LlmServiceMode.KNOWLEDGE_BASED -> buildKnowledgeBasedPrompt(userQuestion, context)
    LlmServiceMode.GENERATIVE_AI -> buildGenerativeAIPrompt(userQuestion, context)
  }
}

private fun buildKnowledgeBasedPrompt(
  userQuestion: String,
  context: String,
): String {
  return if (context.isNotEmpty()) {
    """
            |Контекст из базы знаний:
            |$context
            |
            |Вопрос пользователя: $userQuestion
            |
            |Ответь на вопрос, используя ТОЛЬКО информацию из предоставленного контекста.
            |Если в контексте нет информации для ответа, скажи, что информации недостаточно.
            |Если в контексте есть ссылки, перейди по ним для получения дополнительной информации (исключение - ссылки на Google drive и Youtube).
            """.trimMargin()
  } else {
    """
            |В базе знаний не найдено информации.
            |Вопрос: $userQuestion
            |Объясни, что информации пока нет в базе, но сгенерируй четкий ответ без воды на основании информации из интернета.
            |Не добавляй в ответ Call to action, только ответ на вопрос. У пользователя не будет возможности ответить обратно.
            """.trimMargin()
  }
}

private fun buildGenerativeAIPrompt(
  userQuestion: String,
  context: String,
): String {
  return if (context.isNotEmpty()) {
    """
            |Контекст для ответа:
            |$context
            |
            |Вопрос пользователя: $userQuestion
            |
            |Пожалуйста, предоставь развернутый и информативный ответ на вопрос, используя предоставленную информацию.
            """.trimMargin()
  } else {
    """
            |Вопрос: $userQuestion
            |Пожалуйста, предоставь развернутый и информативный ответ на вопрос, но не длиннее 200 слов.
            """.trimMargin()
  }
}
