package com.minibot.control.session

import com.minibot.control.container.ContainerException
import com.minibot.control.container.ContainerService
import com.minibot.control.model.ModelException
import com.minibot.control.model.ModelService
import com.minibot.control.tool.ApprovalService
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SessionError(
    val code: String,
    val message: String,
    val status: Int,
)

class SessionException(val error: SessionError) : RuntimeException(error.message)

interface SessionOperations {
    fun createSession(request: CreateSessionRequest): SessionRecord
    fun listSessions(): List<SessionRecord>
    fun snapshot(sessionId: String): SessionSnapshot
    fun createTurn(sessionId: String, request: CreateTurnRequest): TurnCreation
    fun cancelTurn(sessionId: String, turnId: String): TurnRecord
    fun events(sessionId: String, afterSeq: Long, limit: Int): List<StoredEvent>
}

class SessionService(
    private val store: SessionStore,
    private val containers: ContainerService,
    private val scheduler: TurnEnqueuer,
    private val events: EventStore,
    private val models: ModelService? = null,
    private val approvals: ApprovalService? = null,
) : SessionOperations {
    private val createLocks = ConcurrentHashMap<String, ReentrantLock>()
    override fun createSession(request: CreateSessionRequest): SessionRecord {
        val profileId = request.profileId.trim()
        if (!isUuid(profileId)) throw badRequest("invalid_profile", "A valid profileId is required")
        val title = request.title.trim().ifBlank { "新会话" }
        if (title.length > 100) throw badRequest("invalid_title", "Title must be at most 100 characters")
        try {
            containers.get(profileId)
        } catch (error: ContainerException) {
            throw SessionException(
                SessionError(
                    code = error.error.code,
                    message = error.error.message,
                    status = error.error.status,
                ),
            )
        }
        val workspaceId = request.workspaceId?.trim()?.takeIf { it.isNotBlank() }
            ?: store.ensureDefaultWorkspace(profileId)
        val modelBindingId = request.modelBindingId?.trim()?.takeIf { it.isNotBlank() }
        if (modelBindingId != null && models != null) {
            try {
                models.resolve(modelBindingId, profileId)
            } catch (error: ModelException) {
                throw SessionException(
                    SessionError(code = error.code, message = error.message ?: "Model binding is unavailable", status = error.status),
                )
            }
        }
        return store.createSession(profileId, workspaceId, title, modelBindingId)
    }

    override fun listSessions(): List<SessionRecord> = store.listSessions()

    override fun snapshot(sessionId: String): SessionSnapshot {
        val session = findSession(sessionId)
        val turns = store.listTurns(sessionId)
        val current = turns.firstOrNull {
            it.status == TurnStatus.RUNNING ||
                it.status == TurnStatus.WAITING_RUNTIME ||
                it.status == TurnStatus.WAITING_APPROVAL
        }
        val queue = turns.filter { it.status == TurnStatus.QUEUED }
        return SessionSnapshot(
            session = session,
            currentTurn = current,
            queue = queue,
            messages = store.latestMessages(sessionId, 100),
            lastSeq = session.lastSeq,
            pendingApprovals = approvals?.listPending()
                ?.filter { it.sessionId == sessionId }
                ?.map { it.id }
                .orEmpty(),
            container = runCatching { containers.get(session.profileId) }.getOrNull(),
            modelBindingId = session.modelBindingId,
        )
    }

    override fun createTurn(sessionId: String, request: CreateTurnRequest): TurnCreation {
        val lock = createLocks.computeIfAbsent(sessionId) { ReentrantLock() }
        return lock.withLock {
            createTurnLocked(sessionId, request)
        }
    }

    private fun createTurnLocked(sessionId: String, request: CreateTurnRequest): TurnCreation {
        findSession(sessionId)
        val clientMessageId = request.clientMessageId.trim()
        if (clientMessageId.isBlank()) throw badRequest("invalid_client_message_id", "clientMessageId is required")
        if (clientMessageId.length > 128) throw badRequest("invalid_client_message_id", "clientMessageId is too long")
        val content = request.content
        if (content.isBlank() && request.attachments.isEmpty()) {
            throw badRequest("empty_message", "Message content or an attachment is required")
        }
        if (content.length > 100_000) throw badRequest("message_too_long", "Message content is too long")

        val existing = store.findTurnByClientMessageId(sessionId, clientMessageId)
        if (existing != null) {
            return TurnCreation(existing, null, emptyList())
        }

        val contentJson = buildJsonObject {
            put("content", content)
            put(
                "attachments",
                buildJsonArray {
                    request.attachments.forEach { add(it) }
                },
            )
        }.toString()
        val creation = store.createTurn(sessionId, clientMessageId, content, contentJson)
        if (creation.events.isNotEmpty()) events.publish(creation.events)
        scheduler.enqueue(creation.turn)
        return creation
    }

    override fun cancelTurn(sessionId: String, turnId: String): TurnRecord {
        val session = findSession(sessionId)
        if (!isUuid(turnId)) throw badRequest("invalid_turn", "A valid turnId is required")
        val turn = store.findTurn(turnId)
            ?: throw SessionException(SessionError("turn_not_found", "Turn not found", 404))
        if (turn.sessionId != session.id) {
            throw SessionException(SessionError("turn_not_found", "Turn not found", 404))
        }
        if (turn.status in TERMINAL_STATUSES) return turn
        if (turn.status != TurnStatus.QUEUED && turn.status != TurnStatus.WAITING_RUNTIME) {
            throw SessionException(
                SessionError("turn_already_running", "A running turn cannot be cancelled yet", 409),
            )
        }
        val cancelled = store.cancelQueuedTurn(sessionId, turnId)
            ?: throw SessionException(SessionError("turn_not_found", "Turn not found", 404))
        events.append(
            sessionId = sessionId,
            turnId = turnId,
            messageId = null,
            type = "turn.interrupted",
            payloadJson = buildJsonObject { put("reason", "cancelled_by_user") }.toString(),
        )
        return cancelled
    }

    override fun events(sessionId: String, afterSeq: Long, limit: Int): List<StoredEvent> {
        findSession(sessionId)
        return store.listEvents(sessionId, afterSeq.coerceAtLeast(0), limit.coerceIn(1, 1_000))
    }

    private fun findSession(sessionId: String): SessionRecord {
        if (!isUuid(sessionId)) throw badRequest("invalid_session", "A valid sessionId is required")
        return store.findSession(sessionId)
            ?: throw SessionException(SessionError("session_not_found", "Session not found", 404))
    }

    private fun badRequest(code: String, message: String): SessionException =
        SessionException(SessionError(code, message, 400))

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private companion object {
        val TERMINAL_STATUSES = setOf(
            TurnStatus.COMPLETED,
            TurnStatus.FAILED,
            TurnStatus.INTERRUPTED,
            TurnStatus.CANCELLED,
        )
    }
}
