package com.vireal.bot.utils

import com.vireal.shared.models.MCPType

enum class BotWaitingState {
  NOTE_TEXT,
  SEARCH_QUERY,
  QUESTION,
  UNSPECIFIED_YET,
  SET_REMINDER_TIME,
}

fun mapWaitingStateToMCPType(state: BotWaitingState): MCPType {
  return when (state) {
    BotWaitingState.NOTE_TEXT -> MCPType.NOTE_SAVING
    BotWaitingState.QUESTION -> MCPType.KNOWLEDGE_BASE_QUERY
    BotWaitingState.SET_REMINDER_TIME -> MCPType.REMINDER_CREATION
    BotWaitingState.SEARCH_QUERY -> MCPType.UNCATEGORIZED
    BotWaitingState.UNSPECIFIED_YET -> MCPType.UNCATEGORIZED
  }
}

enum class CallbackState {
  CONFIRM_ACTION,
  DECLINE_ACTION,
  CANCEL_ACTION,
  SEARCH_TEXT,
  SAVE_NOTE,
  ASK_QUESTION,
  CANCEL_DELETE,
}
