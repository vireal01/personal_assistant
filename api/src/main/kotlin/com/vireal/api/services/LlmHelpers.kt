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
            |${servicePrompt}
            """.trimMargin()
  } else {
    """
            |В базе знаний не найдено информации.
            |Вопрос: $userQuestion
            |Объясни, что информации пока нет в базе, но сгенерируй четкий ответ без воды на основании информации из интернета.
            |${servicePrompt}
            """.trimMargin().plus(servicePrompt)
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
            |${servicePrompt}
            """.trimMargin()
  } else {
    """
            |Вопрос: $userQuestion
            |${servicePrompt}
            """.trimMargin()
  }
}

private val servicePrompt = """
    |
    |Service Instructions:
    |Ты виртуальный помощник, который помогает пользователям, используя базу знаний и возможности генеративного ИИ.
    |Твоя задача - предоставлять точные, емкие и полезные ответы на вопросы пользователей.
    |Всегда старайся быть полезным и информативным.
    |Ответ должен быть не длиннее 200 слов. Учи, что пользователь не сможет ответить обратно. Не добавляй Call to action.
    """.trimMargin()
