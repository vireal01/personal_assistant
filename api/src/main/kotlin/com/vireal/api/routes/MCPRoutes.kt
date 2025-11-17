package com.vireal.api.routes

import com.vireal.api.mcp.MCPService
import com.vireal.shared.models.MCPToolRequest
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * MCP маршруты для работы с инструментами
 */
fun Route.mcpRoutes() {
    val mcpService = MCPService()

    route("/api/mcp") {

        /**
         * Получить список доступных инструментов
         */
        get("/tools") {
            val tools = mcpService.getAvailableTools()
            call.respond(tools)
        }

        /**
         * Выполнить инструмент MCP
         */
        post("/tools/execute") {
            val request = call.receive<MCPToolRequest>()
            val result = mcpService.executeTool(request)
            call.respond(result)
        }

        /**
         * Упрощенный эндпоинт для запроса с поиском в базе знаний
         */
        post("/query/with-context") {
            val params = call.receive<JsonObject>()
            val userId = params["userId"]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("Параметр 'userId' является обязательным")

            val question = params["question"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Параметр 'question' является обязательным")

            val tags = params["tags"]?.jsonArray?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList()

            val category = params["category"]?.jsonPrimitive?.contentOrNull

            val request = MCPToolRequest(
                name = MCPService.TOOL_QUERY_WITH_CONTEXT,
                arguments = buildMap {
                    put("userId", JsonPrimitive(userId))
                    put("question", JsonPrimitive(question))
                    if (tags.isNotEmpty()) {
                        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
                    }
                    if (category != null) {
                        put("category", JsonPrimitive(category))
                    }
                }
            )

            val result = mcpService.executeTool(request)
            call.respond(result)
        }

        /**
         * Упрощенный эндпоинт для запроса без поиска в базе знаний
         */
        post("/query/without-context") {
            val params = call.receive<JsonObject>()
            val question = params["question"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Параметр 'question' является обязательным")

            val context = params["context"]?.jsonPrimitive?.contentOrNull ?: ""

            val request = MCPToolRequest(
                name = MCPService.TOOL_QUERY_WITHOUT_CONTEXT,
                arguments = mapOf(
                    "question" to JsonPrimitive(question),
                    "context" to JsonPrimitive(context)
                )
            )

            val result = mcpService.executeTool(request)
            call.respond(result)
        }
    }
}
