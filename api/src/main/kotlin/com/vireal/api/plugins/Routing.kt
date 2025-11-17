package com.vireal.api.plugins

import com.vireal.api.routes.healthRoute
import com.vireal.api.routes.mcpRoutes
import com.vireal.api.routes.notesRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
  routing {
    mcpRoutes()
    notesRoutes()
    healthRoute()
  }
}
