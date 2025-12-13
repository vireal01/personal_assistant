package com.vireal.api.routes

import com.vireal.api.mcp.MCPService
import com.vireal.shared.models.DecideMCPToolResult
import com.vireal.shared.models.MCPDecideToolRequest
import com.vireal.shared.models.MCPQueryWithContextRequest
import com.vireal.shared.models.MCPQueryWithoutContextRequest
import com.vireal.shared.models.MCPToolRequest
import com.vireal.shared.models.MCPToolResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProcessRequest(val query: String, val userId: Long)

/**
 * Главный роут для обработки всех запросов пользователя через MCP.
 */
fun Route.mcpRoutes() {
  val mcpService = MCPService()
  val json = Json { ignoreUnknownKeys = true }

  route("/api/mcp") {
    // Старый процессинг через LLM-решение
    post("/process") {
      val raw = call.receiveText()
      println("[MCPRoutes] POST /api/mcp/process called, raw=$raw")
      val request = json.decodeFromString<ProcessRequest>(raw)
      println("[MCPRoutes] /process userId=${request.userId}, query=\"${request.query}\"")
      val result = mcpService.processUserQuery(request.query, request.userId)
      println("[MCPRoutes] /process result isError=${result.isError}")
      call.respond<MCPToolResult>(HttpStatusCode.OK, result)
    }

    // Новый: список доступных инструментов
    get("/tools") {
      println("[MCPRoutes] GET /api/mcp/tools called")
      val tools = mcpService.getAvailableTools()
      println("[MCPRoutes] /tools returned ${tools.size} tools: ${tools.map { it.name }}")
      call.respond(tools)
    }

    // Новый: список доступных инструментов
    get("/tools/decide-tool") {
      println("[MCPRoutes] GET /api/mcp/tools/decide-tool called")
      val req = call.receive<MCPDecideToolRequest>()
      val result = mcpService.decideToolToUse(req)
      call.respond<DecideMCPToolResult>(result)
    }



    // Новый: выполнить инструмент по имени
    post("/tools/execute") {
      val req = call.receive<MCPToolRequest>()
      println("[MCPRoutes] POST /api/mcp/tools/execute called, name=${req.type.name}, argsKeys=${req.arguments.keys}")
      val result = mcpService.executeTool(req)
      println("[MCPRoutes] /tools/execute result isError=${result.isError}")
      call.respond<MCPToolResult>(result)
    }

    // Новый: запрос с контекстом (ожидается ботом)
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

    // Новый: запрос без контекста (ожидается ботом)
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
