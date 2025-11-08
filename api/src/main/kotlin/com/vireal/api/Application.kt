package com.vireal.api

import com.vireal.api.plugins.configureCORS
import com.vireal.api.plugins.configureDatabase
import com.vireal.api.plugins.configureRouting
import com.vireal.api.plugins.configureSerialization
import com.vireal.api.plugins.configureStatusPages
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
  embeddedServer(
    Netty,
    port = System.getenv("PORT")?.toInt() ?: 8080,
    host = "0.0.0.0",
    module = Application::module
  ).start(wait = true)
}

fun Application.module() {
  configureSerialization()
  configureCORS()
  configureStatusPages()
  configureDatabase()
  configureRouting()
}
