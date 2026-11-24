package com.minibot.control.api

import com.minibot.control.model.CreateCredentialRequest
import com.minibot.control.model.CreateModelBindingRequest
import com.minibot.control.model.CreateModelDefinitionRequest
import com.minibot.control.model.ModelException
import com.minibot.control.model.ModelService
import com.minibot.control.pairing.PairingService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.modelRoutes(
    pairingService: PairingService,
    models: ModelService,
) {
    route("/model-credentials") {
        get {
            if (!call.requireModelDevice(pairingService)) return@get
            call.respond(models.listCredentials())
        }

        post {
            if (!call.requireModelDevice(pairingService)) return@post
            val request = call.receive<CreateCredentialRequest>()
            try {
                call.respond(HttpStatusCode.Created, models.createCredential(request))
            } catch (error: ModelException) {
                call.respond(HttpStatusCode.fromValue(error.status), ErrorResponse(error.code, error.message ?: "Model error"))
            }
        }
    }

    route("/model-definitions") {
        get {
            if (!call.requireModelDevice(pairingService)) return@get
            call.respond(models.listDefinitions())
        }

        post {
            if (!call.requireModelDevice(pairingService)) return@post
            val request = call.receive<CreateModelDefinitionRequest>()
            try {
                call.respond(HttpStatusCode.Created, models.createDefinition(request))
            } catch (error: ModelException) {
                call.respond(HttpStatusCode.fromValue(error.status), ErrorResponse(error.code, error.message ?: "Model error"))
            }
        }
    }

    route("/model-bindings") {
        get {
            if (!call.requireModelDevice(pairingService)) return@get
            val profileId = call.request.queryParameters["profileId"]
            if (profileId.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_profile", "profileId is required"),
                )
                return@get
            }
            try {
                call.respond(models.listBindings(profileId))
            } catch (error: ModelException) {
                call.respond(HttpStatusCode.fromValue(error.status), ErrorResponse(error.code, error.message ?: "Model error"))
            }
        }

        post {
            if (!call.requireModelDevice(pairingService)) return@post
            val request = call.receive<CreateModelBindingRequest>()
            try {
                call.respond(HttpStatusCode.Created, models.createBinding(request))
            } catch (error: ModelException) {
                call.respond(HttpStatusCode.fromValue(error.status), ErrorResponse(error.code, error.message ?: "Model error"))
            }
        }

        delete("/{id}") {
            if (!call.requireModelDevice(pairingService)) return@delete
            val bindingId = call.parameters["id"]?.takeIf { it.isNotBlank() }
            if (bindingId == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_binding", "Binding id is required"),
                )
                return@delete
            }
            try {
                models.deleteBinding(bindingId)
                call.respond(HttpStatusCode.NoContent)
            } catch (error: ModelException) {
                call.respond(HttpStatusCode.fromValue(error.status), ErrorResponse(error.code, error.message ?: "Model error"))
            }
        }
    }
}

private suspend fun ApplicationCall.requireModelDevice(pairingService: PairingService): Boolean {
    val token = request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (token == null || pairingService.authenticate(token) == null) {
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse("unauthorized", "A valid device token is required"),
        )
        return false
    }
    return true
}
