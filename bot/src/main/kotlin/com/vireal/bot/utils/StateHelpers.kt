package com.vireal.bot.utils

import com.vireal.shared.models.MCPType

enum class BotWaitingState {
  NOTE_SAVING,
  SEARCH_NOTES,
  KNOWLEDGE_BASE_QUERY,
  UNSPECIFIED_YET,
  SET_REMINDER_TIME,
}

fun mapWaitingStateToMCPType(state: BotWaitingState): MCPType {
  return when (state) {
    BotWaitingState.NOTE_SAVING -> MCPType.NOTE_SAVING
    BotWaitingState.KNOWLEDGE_BASE_QUERY -> MCPType.KNOWLEDGE_BASE_QUERY
    BotWaitingState.SET_REMINDER_TIME -> MCPType.REMINDER_CREATION
    BotWaitingState.SEARCH_NOTES -> MCPType.UNCATEGORIZED
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
