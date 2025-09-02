package com.vireal.routes

import com.vireal.data.models.QueryRequest
import com.vireal.services.QueryService
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