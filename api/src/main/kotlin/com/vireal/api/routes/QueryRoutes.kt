package com.vireal.api.routes

import com.vireal.api.services.QueryService
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.vireal.shared.models.QueryRequest

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