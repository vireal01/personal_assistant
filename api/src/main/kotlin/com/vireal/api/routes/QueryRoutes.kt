package com.vireal.api.routes

import com.vireal.api.controllers.MCPController
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.vireal.shared.models.QueryRequest

/**
 * Маршруты для запросов - обратная совместимость
 * Теперь использует MCP архитектуру под капотом
 */
fun Route.queryRoutes() {
  val mcpController = MCPController()

  route("/api/query") {
    post {
      try {
        val request = call.receive<QueryRequest>()
        val response = mcpController.queryWithKnowledgeBase(
          userId = request.userId,
          question = request.question
        )
        call.respond(response)
      } catch (e: Exception) {
        call.respond(
          status = io.ktor.http.HttpStatusCode.InternalServerError,
          message = mapOf("error" to "Internal server error: ${e.message}")
        )
      }
    }
  }

  route("/api/queryLlm") {
    post {
      try {
        val request = call.receive<QueryRequest>()
        val response = mcpController.queryWithoutKnowledgeBase(
          question = request.question,
          context = request.extraContext ?: ""
        )
        call.respond(response)
      } catch (e: Exception) {
        call.respond(
          status = io.ktor.http.HttpStatusCode.InternalServerError,
          message = mapOf("error" to "Internal server error: ${e.message}")
        )
      }
    }
  }
}
