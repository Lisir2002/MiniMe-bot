package com.minibot.control.api

import com.minibot.control.pairing.PairingService
import com.minibot.control.session.CreateSessionRequest
import com.minibot.control.session.CreateTurnRequest
import com.minibot.control.session.EventHub
import com.minibot.control.session.SessionException
import com.minibot.control.session.SessionOperations
import com.minibot.control.session.SessionSummary
import com.minibot.control.session.StoredEvent
import com.minibot.control.session.toSummary
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.Writer

@Serializable
data class TurnCreatedResponse(
    val turnId: String,
    val seq: Long,
    val status: String,
)

@Serializable
data class EventEnvelope(
    val id: String,
    val sessionId: String,
    val seq: Long,
    val turnId: String?,
    val messageId: String?,
    val type: String,
    val payload: JsonElement,
    val createdAt: String,
)

fun Route.sessionRoutes(
    pairingService: PairingService,
    sessions: SessionOperations,
    eventHub: EventHub,
    json: Json,
) {
    route("/sessions") {
        get {
            if (!call.requireSessionDevice(pairingService)) return@get
            call.respond(sessions.listSessions().map { it.toSummary() })
        }

        post {
            if (!call.requireSessionDevice(pairingService)) return@post
            val request = call.receive<CreateSessionRequest>()
            try {
                call.respond(HttpStatusCode.Created, sessions.createSession(request))
            } catch (error: SessionException) {
                call.respondSessionError(error)
            }
        }

        get("/{id}/snapshot") {
            if (!call.requireSessionDevice(pairingService)) return@get
            val sessionId = call.sessionId() ?: return@get
            try {
                call.respond(sessions.snapshot(sessionId))
            } catch (error: SessionException) {
                call.respondSessionError(error)
            }
        }

        post("/{id}/turns") {
            if (!call.requireSessionDevice(pairingService)) return@post
            val sessionId = call.sessionId() ?: return@post
            val request = call.receive<CreateTurnRequest>()
            try {
                val creation = sessions.createTurn(sessionId, request)
                call.respond(
                    TurnCreatedResponse(
                        turnId = creation.turn.id,
                        seq = creation.turn.createdSeq ?: 0,
                        status = creation.turn.status.name,
                    ),
                )
            } catch (error: SessionException) {
                call.respondSessionError(error)
            }
        }

        post("/{id}/turns/{turnId}/cancel") {
            if (!call.requireSessionDevice(pairingService)) return@post
            val sessionId = call.sessionId() ?: return@post
            val turnId = call.parameters["turnId"]?.takeIf { it.isNotBlank() }
            if (turnId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_turn", "Turn id is required"))
                return@post
            }
            try {
                call.respond(sessions.cancelTurn(sessionId, turnId))
            } catch (error: SessionException) {
                call.respondSessionError(error)
            }
        }

        get("/{id}/events") {
            if (!call.requireSessionDevice(pairingService)) return@get
            val sessionId = call.sessionId() ?: return@get
            val afterSeq = call.request.queryParameters["afterSeq"]?.toLongOrNull() ?: 0L
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
            try {
                call.respond(loadEventEnvelopes(sessionId, afterSeq, limit, sessions, json))
            } catch (error: SessionException) {
                call.respondSessionError(error)
            }
        }

        get("/{id}/stream") {
            if (!call.requireSessionDevice(pairingService)) return@get
            val sessionId = call.sessionId() ?: return@get
            val afterSeq = call.request.queryParameters["afterSeq"]?.toLongOrNull() ?: 0L
            val channel = eventHub.subscribe(sessionId)
            val backlog = try {
                sessions.events(sessionId, afterSeq, 1_000)
            } catch (error: SessionException) {
                eventHub.unsubscribe(sessionId, channel)
                call.respondSessionError(error)
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header("X-Accel-Buffering", "no")
            call.respondTextWriter(ContentType.Text.EventStream) {
                try {
                    backlog.forEach { writeSseEvent(this, it, json) }
                    while (true) {
                        val event = withTimeoutOrNull(15_000) { channel.receive() }
                        if (event == null) {
                            write(": ping\n\n")
                            flush()
                        } else {
                            writeSseEvent(this, event, json)
                        }
                    }
                } finally {
                    eventHub.unsubscribe(sessionId, channel)
                }
            }
        }
    }
}

private suspend fun loadEventEnvelopes(
    sessionId: String,
    afterSeq: Long,
    limit: Int,
    sessions: SessionOperations,
    json: Json,
): List<EventEnvelope> {
    return sessions.events(sessionId, afterSeq, limit).map { it.toEnvelope(json) }
}

private fun StoredEvent.toEnvelope(json: Json): EventEnvelope {
    return EventEnvelope(
        id = id,
        sessionId = sessionId,
        seq = seq,
        turnId = turnId,
        messageId = messageId,
        type = type,
        payload = runCatching { json.parseToJsonElement(payloadJson) }.getOrDefault(JsonNull),
        createdAt = createdAt,
    )
}

private fun writeSseEvent(writer: Writer, event: StoredEvent, json: Json) {
    writer.write("id: ${event.seq}\n")
    writer.write("event: ${event.type}\n")
    writer.write("data: ${json.encodeToString(EventEnvelope.serializer(), event.toEnvelope(json))}\n\n")
    writer.flush()
}

private fun ApplicationCall.sessionId(): String? {
    val id = parameters["id"]
    if (id.isNullOrBlank()) {
        return null
    }
    return id
}

private suspend fun ApplicationCall.requireSessionDevice(pairingService: PairingService): Boolean {
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

private suspend fun ApplicationCall.respondSessionError(error: SessionException) {
    respond(
        HttpStatusCode.fromValue(error.error.status),
        ErrorResponse(error.error.code, error.error.message),
    )
}
