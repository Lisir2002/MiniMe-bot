package com.minibot.control.tool

import com.minibot.control.session.EventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

class ApprovalService(
    private val store: ApprovalStore,
    private val events: EventStore,
) : AutoCloseable {
    private val waiters = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<ApprovalStatus>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }
    var onResolved: ((ApprovalRecord) -> Unit)? = null

    suspend fun request(
        turnId: String,
        sessionId: String,
        request: ToolApprovalRequest,
        timeoutMs: Long,
        waitForDecision: Boolean = true,
    ): ApprovalRecord {
        val expiresAt = Instant.now().plus(timeoutMs, ChronoUnit.MILLIS).toString()
        val approval = store.create(
            turnId = turnId,
            sessionId = sessionId,
            requestJson = buildJsonObject {
                put("callId", request.callId)
                put("tool", request.tool)
                put("command", request.command)
                put("risk", request.risk.name)
                put("timeoutMs", request.timeoutMs)
            }.toString(),
            expiresAt = expiresAt,
        )
        events.append(
            sessionId = sessionId,
            turnId = turnId,
            messageId = null,
            type = "approval.requested",
            payloadJson = buildJsonObject {
                put("approvalId", approval.id)
                put("callId", request.callId)
                put("tool", request.tool)
                put("command", request.command)
                put("risk", request.risk.name)
                put("expiresAt", approval.expiresAt)
            }.toString(),
        )

        if (!waitForDecision) return approval

        val deferred = CompletableDeferred<ApprovalStatus>()
        waiters[approval.id] = deferred
        store.find(approval.id)?.takeIf { it.status != ApprovalStatus.PENDING }?.let {
            deferred.complete(it.status)
        }
        val decided = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        waiters.remove(approval.id)
        val finalRecord = if (decided == null) {
            store.markExpired(approval.id) ?: store.find(approval.id) ?: approval
        } else {
            store.find(approval.id) ?: approval
        }
        if (decided == null) {
            emitResolved(finalRecord, request.callId)
        }
        return finalRecord
    }

    fun find(approvalId: String): ApprovalRecord? = store.find(approvalId)

    fun listPending(): List<ApprovalRecord> = store.listPending()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching { expireDue() }
        scope.launch {
            while (isActive) {
                delay(EXPIRE_INTERVAL_MS)
                runCatching { expireDue() }
            }
        }
    }

    fun expireDue() {
        val now = Instant.now()
        store.listPending().forEach { approval ->
            val expiresAt = runCatching { Instant.parse(approval.expiresAt) }.getOrNull() ?: return@forEach
            if (expiresAt.isAfter(now)) return@forEach
            val expired = store.markExpired(approval.id) ?: return@forEach
            if (expired.status == ApprovalStatus.EXPIRED && approval.status == ApprovalStatus.PENDING) {
                val callId = approvalCallId(approval)
                emitResolved(expired, callId)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private fun emitResolved(approval: ApprovalRecord, callId: String?) {
        events.append(
            sessionId = approval.sessionId,
            turnId = approval.turnId,
            messageId = null,
            type = "approval.resolved",
            payloadJson = buildJsonObject {
                put("approvalId", approval.id)
                put("callId", callId ?: "")
                put("status", approval.status.name)
                put("decision", approval.decision ?: "")
            }.toString(),
        )
        onResolved?.invoke(approval)
    }

    private fun approvalCallId(approval: ApprovalRecord): String? {
        return runCatching {
            json.parseToJsonElement(approval.requestJson).jsonObject["callId"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    fun decide(
        turnId: String,
        approvalId: String,
        decision: String,
    ): ApprovalDecisionResult {
        val approval = store.find(approvalId)
            ?: throw ApprovalException("approval_not_found", "Approval not found", 404)
        if (approval.turnId != turnId) {
            throw ApprovalException("approval_turn_mismatch", "Approval belongs to another turn", 409)
        }
        val normalized = decision.trim().lowercase()
        if (normalized !in setOf("approve", "deny")) {
            throw ApprovalException("invalid_decision", "decision must be approve or deny", 400)
        }
        if (approval.status != ApprovalStatus.PENDING) {
            if (approval.status == ApprovalStatus.EXPIRED) {
                throw ApprovalException("approval_expired", "Approval expired", 409)
            }
            return ApprovalDecisionResult(approval, alreadyDecided = true)
        }
        val status = if (normalized == "approve") ApprovalStatus.APPROVED else ApprovalStatus.DENIED
        val updated = store.decide(approvalId, status, normalized, null)
            ?: throw ApprovalException("approval_not_found", "Approval not found", 404)
        if (updated.status == status) {
            waiters[approvalId]?.complete(status)
            emitResolved(updated, null)
        }
        return ApprovalDecisionResult(updated, alreadyDecided = false)
    }
}

private const val EXPIRE_INTERVAL_MS = 15_000L
