package com.vireal.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class HealthStatus(
  val status: String,
  val timestamp: Long
)

fun Route.healthRoute() {
  get("/health") {
    call.respond(
      HttpStatusCode.OK,
      HealthStatus(
        status = "healthy",
        timestamp = System.currentTimeMillis()
      )
    )
  }
}
