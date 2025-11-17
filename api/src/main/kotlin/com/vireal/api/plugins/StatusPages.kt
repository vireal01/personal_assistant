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
    // Перехватываем ошибки валидации аргументов (400 Bad Request)
    exception<IllegalArgumentException> { call, cause ->
      call.respond(
        status = HttpStatusCode.BadRequest,
        message = mapOf("error" to (cause.message ?: "Некорректный запрос"))
      )
    }

    // Общая обработка остальных ошибок (500 Internal Server Error)
    exception<Throwable> { call, cause ->
      // Важно логировать ошибку на сервере для последующего анализа
      call.application.environment.log.error("Unhandled exception", cause)
      call.respond(
        status = HttpStatusCode.InternalServerError,
        message = mapOf("error" to "Внутренняя ошибка сервера")
      )
    }
  }
}
