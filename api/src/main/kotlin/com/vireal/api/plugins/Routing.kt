package com.vireal.api.plugins

import com.vireal.api.routes.healthRoute
import com.vireal.api.routes.mcpRoutes
import com.vireal.api.routes.notesRoutes
import com.vireal.api.routes.queryRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
  routing {
    mcpRoutes()      // MCP маршруты - новая архитектура
    notesRoutes()
    queryRoutes()    // Оставляем для обратной совместимости
    healthRoute()
  }
}
