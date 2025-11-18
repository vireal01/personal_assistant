package com.vireal.api.routes

import com.vireal.api.mcp.MCPService
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
        post("/process") {
            val request = call.receive<ProcessRequest>()
            val result = mcpService.processUserQuery(request.query, request.userId)
            call.respond(result)
        }
    }
}


