package com.vireal.api.routes

import com.vireal.api.mcp.MCPService
import com.vireal.shared.models.MCPQueryWithContextRequest
import com.vireal.shared.models.MCPQueryWithoutContextRequest
import com.vireal.shared.models.MCPToolRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ProcessRequest(val query: String, val userId: Long)

/**
 * Главный роут для обработки всех запросов пользователя через MCP.
 */
fun Route.mcpRoutes() {
  val mcpService = MCPService()

  route("/api/mcp") {
    // Старый процессинг через LLM-решение
    post("/process") {
      val request = call.receive<ProcessRequest>()
      val result = mcpService.processUserQuery(request.query, request.userId)
      call.respond(result)
    }

    // Новый: список доступных инструментов
    get("/tools") {
      val tools = mcpService.getAvailableTools()
      call.respond(tools)
    }

    // Новый: выполнить инструмент по имени
    post("/tools/execute") {
      val req = call.receive<MCPToolRequest>()
      val result = mcpService.executeTool(req)
      call.respond(result)
    }

    // Новый: запрос с контекстом (ожидается ботом)
    post("/query/with-context") {
      val req = call.receive<MCPQueryWithContextRequest>()
      val result = mcpService.queryWithKnowledgeBase(
        userId = req.userId,
        question = req.question,
        tags = req.tags,
        category = req.category
      )
      call.respond(HttpStatusCode.OK, result)
    }

    // Новый: запрос без контекста (ожидается ботом)
    post("/query/without-context") {
      val req = call.receive<MCPQueryWithoutContextRequest>()
      val result = mcpService.queryWithoutKnowledgeBase(
        question = req.question,
        context = req.context
      )
      call.respond(HttpStatusCode.OK, result)
    }
  }
}
