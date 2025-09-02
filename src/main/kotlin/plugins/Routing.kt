package com.vireal.plugins

import com.vireal.routes.*
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        notesRoutes()
        queryRoutes()
        healthRoute()
    }
}