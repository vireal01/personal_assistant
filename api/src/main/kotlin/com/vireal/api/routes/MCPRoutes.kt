package com.vireal.api.routes

import com.vireal.api.mcp.MCPService
import com.vireal.shared.models.DecideMCPToolResult
import com.vireal.shared.models.MCPDecideToolRequest
import com.vireal.shared.models.MCPQueryWithContextRequest
import com.vireal.shared.models.MCPQueryWithoutContextRequest
import com.vireal.shared.models.MCPSaveNoteRequest
import com.vireal.shared.models.MCPToolResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Главный роут для обработки всех запросов пользователя через MCP.
 */
fun Route.mcpRoutes() {
  val mcpService = MCPService()

  route("/api/mcp") {

    // Список доступных инструментов
    get("/tools") {
      println("[MCPRoutes] GET /api/mcp/tools called")
      val tools = mcpService.getAvailableTools()
      println("[MCPRoutes] /tools returned ${tools.size} tools: ${tools.map { it.name }}")
      call.respond(tools)
    }

    post("/tool/save-note") {
      println("[MCPRoutes] POST /api/mcp/tool/save-note called")
      val req = call.receive<MCPSaveNoteRequest>()
      val result = mcpService.saveNote(
        userId = req.userId,
        text = req.text,
        tags = req.tags,
        category = req.category,
      )
      println("[MCPRoutes] /tool/save-note result isError=${result.isError}")
      call.respond<MCPToolResult>(HttpStatusCode.OK, result)
    }

    // Список доступных инструментов
    post("/intent/decide") {
      println("[MCPRoutes] POST /api/mcp/intent/decide called")
      val req = call.receive<MCPDecideToolRequest>()
      val result = mcpService.decideToolToUse(req)
      call.respond<DecideMCPToolResult>(result)
    }

    // Запрос с контекстом (ожидается ботом)
    post("/query/with-context") {
      val req = call.receive<MCPQueryWithContextRequest>()
      println("[MCPRoutes] POST /api/mcp/query/with-context called, userId=${req.userId}, question=\"${req.question}\" tags=${req.tags} category=${req.category}")
      val result = mcpService.queryWithKnowledgeBase(
        userId = req.userId,
        question = req.question,
        tags = req.tags,
        category = req.category
      )
      println("[MCPRoutes] /query/with-context result isError=${result.isError}")
      call.respond<MCPToolResult>(HttpStatusCode.OK, result)
    }

    // Запрос без контекста (ожидается ботом)
    post("/query/without-context") {
      val req = call.receive<MCPQueryWithoutContextRequest>()
      println("[MCPRoutes] POST /api/mcp/query/without-context called, question=\"${req.question}\" contextLen=${req.context.length}")
      val result = mcpService.queryWithoutKnowledgeBase(
        question = req.question,
        context = req.context
      )
      println("[MCPRoutes] /query/without-context result isError=${result.isError}")
      call.respond<MCPToolResult>(HttpStatusCode.OK, result)
    }
  }
}
