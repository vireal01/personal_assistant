package com.vireal.api.mcp

import com.vireal.api.services.Message
import com.vireal.shared.models.MCPType

internal fun parseMCPType(input: Message): MCPType {
  return when (input.content?.uppercase()) {
    "KNOWLEDGE_BASE_QUERY" -> MCPType.KNOWLEDGE_BASE_QUERY
    "REMINDER_CREATION" -> MCPType.REMINDER_CREATION
    "NOTE_SAVING" -> MCPType.NOTE_SAVING
    else -> MCPType.UNCATEGORIZED
  }
}

internal fun parseMCPType(name: String): MCPType {
  return when (name.uppercase()) {
    "KNOWLEDGE_BASE_QUERY" -> MCPType.KNOWLEDGE_BASE_QUERY
    "REMINDER_CREATION" -> MCPType.REMINDER_CREATION
    "NOTE_SAVING" -> MCPType.NOTE_SAVING
    else -> MCPType.UNCATEGORIZED
  }
}
