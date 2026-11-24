package com.minibot.control.api

import com.minibot.control.pairing.PairingService
import com.minibot.control.tool.ApprovalException
import com.minibot.control.tool.ApprovalService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class ApprovalDecisionRequest(
    val decision: String,
    val reason: String? = null,
)

fun Route.approvalRoutes(
    pairingService: PairingService,
    approvals: ApprovalService,
) {
    get("/approvals") {
        if (!call.requireApprovalDevice(pairingService)) return@get
        val status = call.request.queryParameters["status"]?.trim()?.lowercase()
        if (status != null && status != "pending") {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_status", "Only status=pending is supported"),
            )
            return@get
        }
        call.respond(approvals.listPending())
    }

    post("/turns/{turnId}/approvals/{approvalId}") {
        if (!call.requireApprovalDevice(pairingService)) return@post
        val turnId = call.parameters["turnId"]?.takeIf { it.isNotBlank() }
        val approvalId = call.parameters["approvalId"]?.takeIf { it.isNotBlank() }
        if (turnId == null || approvalId == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request", "turnId and approvalId are required"),
            )
            return@post
        }
        val request = call.receive<ApprovalDecisionRequest>()
        try {
            call.respond(approvals.decide(turnId, approvalId, request.decision))
        } catch (error: ApprovalException) {
            call.respond(
                HttpStatusCode.fromValue(error.status),
                ErrorResponse(error.code, error.message ?: "Approval error"),
            )
        }
    }
}

private suspend fun ApplicationCall.requireApprovalDevice(pairingService: PairingService): Boolean {
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
