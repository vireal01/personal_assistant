package com.vireal.api.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String, val message: String)

fun Application.configureStatusPages() {
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      call.application.environment.log.error("Unhandled exception", cause)
      call.respond(
        HttpStatusCode.InternalServerError,
        ErrorResponse("internal_error", cause.localizedMessage ?: "Unknown error")
      )
    }

    exception<IllegalArgumentException> { call, cause ->
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse("bad_request", cause.message ?: "Invalid request")
      )
    }
  }
}
