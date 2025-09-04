package com.vireal.api.plugins

import com.vireal.api.data.database.DatabaseFactory
import io.ktor.server.application.*

fun Application.configureDatabase() {
    DatabaseFactory.init(environment.config)
}
