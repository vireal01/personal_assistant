package com.vireal.api.routes

import com.vireal.api.services.QueryService
import com.vireal.shared.models.QueryRequest
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.queryRoutes() {
  val queryService = QueryService()

  route("/api/query") {
    post {
      val request = call.receive<QueryRequest>()
      val response = queryService.processQuery(request.userId, request.question)
      call.respond(response)
    }
  }
}
