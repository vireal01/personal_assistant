package com.vireal.routes

import com.vireal.data.models.CreateNoteRequest
import com.vireal.services.NotesService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.notesRoutes() {
    val notesService = NotesService()

    route("/api/notes") {
        post {
            val request = call.receive<CreateNoteRequest>()
            val response = notesService.addNote(request.userId, request.content)

            if (response.success) {
                call.respond(HttpStatusCode.Created, response)
            } else {
                call.respond(HttpStatusCode.BadRequest, response)
            }
        }

        get("/{userId}") {
            val userId = call.parameters["userId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid user ID")

            val notes = notesService.getUserNotes(userId)
            call.respond(notes)
        }

        get("/search") {
            val userId = call.parameters["userId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid user ID")
            val query = call.parameters["q"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Query parameter required")

            val result = notesService.searchNotes(userId, query)
            call.respond(result)
        }
    }
}