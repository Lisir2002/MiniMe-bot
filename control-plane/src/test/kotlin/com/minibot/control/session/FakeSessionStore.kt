package com.minibot.control.session

import java.time.Instant
import java.util.UUID

class FakeSessionStore : SessionStore {
    private val sessions = mutableMapOf<String, SessionRecord>()
    private val turns = mutableMapOf<String, TurnRecord>()
    private val messages = mutableMapOf<String, MessageRecord>()
    private val eventLog = mutableListOf<StoredEvent>()
    private val checkpoints = mutableMapOf<String, TurnCheckpoint>()
    private var order = 0L

    override fun ensureDefaultWorkspace(profileId: String): String = "workspace-$profileId"

    override fun createSession(
        profileId: String,
        workspaceId: String?,
        title: String,
        modelBindingId: String?,
    ): SessionRecord {
        val session = SessionRecord(
            id = UUID.randomUUID().toString(),
            profileId = profileId,
            workspaceId = workspaceId,
            title = title,
            modelBindingId = modelBindingId,
            lastSeq = 0,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        )
        sessions[session.id] = session
        return session
    }

    override fun listSessions(): List<SessionRecord> = sessions.values.toList()

    override fun findSession(sessionId: String): SessionRecord? = sessions[sessionId]

    override fun findTurn(turnId: String): TurnRecord? = turns[turnId]

    override fun findTurnByClientMessageId(sessionId: String, clientMessageId: String): TurnRecord? {
        return turns.values.firstOrNull {
            it.sessionId == sessionId && it.clientMessageId == clientMessageId
        }
    }

    override fun createTurn(
        sessionId: String,
        clientMessageId: String,
        content: String,
        contentJson: String,
    ): TurnCreation {
        val existing = findTurnByClientMessageId(sessionId, clientMessageId)
        if (existing != null) return TurnCreation(existing, null, emptyList())

        val session = sessions.getValue(sessionId)
        val base = session.lastSeq + 1
        val turnId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val createdAt = Instant.now().toString()
        val turn = TurnRecord(
            id = turnId,
            sessionId = sessionId,
            clientMessageId = clientMessageId,
            status = TurnStatus.QUEUED,
            queueOrder = order++,
            createdSeq = base,
            createdAt = createdAt,
            startedAt = null,
            finishedAt = null,
            errorCode = null,
            errorMessage = null,
        )
        turns[turnId] = turn
        messages[messageId] = MessageRecord(
            id = messageId,
            sessionId = sessionId,
            turnId = turnId,
            role = "user",
            content = content,
            contentJson = contentJson,
            createdAt = createdAt,
        )
        val turnEvent = StoredEvent(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            seq = base,
            turnId = turnId,
            messageId = null,
            type = "turn.created",
            payloadJson = """{"clientMessageId":"$clientMessageId"}""",
            createdAt = createdAt,
        )
        val messageEvent = StoredEvent(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            seq = base + 1,
            turnId = turnId,
            messageId = messageId,
            type = "message.completed",
            payloadJson = """{"messageId":"$messageId","role":"user"}""",
            createdAt = createdAt,
        )
        eventLog += turnEvent
        eventLog += messageEvent
        sessions[sessionId] = session.copy(lastSeq = base + 1, updatedAt = createdAt)
        return TurnCreation(turn, messages[messageId], listOf(turnEvent, messageEvent))
    }

    override fun listQueuedTurns(): List<TurnRecord> {
        return turns.values.filter { it.status == TurnStatus.QUEUED }.sortedBy { it.queueOrder }
    }

    override fun listTurns(sessionId: String): List<TurnRecord> {
        return turns.values.filter { it.sessionId == sessionId }.sortedBy { it.queueOrder }
    }

    override fun updateTurnStatus(
        turnId: String,
        status: TurnStatus,
        errorCode: String?,
        errorMessage: String?,
    ): TurnRecord? {
        val turn = turns[turnId] ?: return null
        val now = Instant.now().toString()
        val updated = turn.copy(
            status = status,
            startedAt = if (status == TurnStatus.RUNNING) turn.startedAt ?: now else turn.startedAt,
            finishedAt = if (status in TERMINAL) now else turn.finishedAt,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
        turns[turnId] = updated
        return updated
    }

    override fun cancelQueuedTurn(sessionId: String, turnId: String): TurnRecord? {
        val turn = turns[turnId] ?: return null
        if (turn.sessionId != sessionId || turn.status !in setOf(TurnStatus.QUEUED, TurnStatus.WAITING_RUNTIME)) {
            return null
        }
        return updateTurnStatus(turnId, TurnStatus.CANCELLED)
    }

    override fun createMessage(
        sessionId: String,
        turnId: String?,
        role: String,
        content: String,
        contentJson: String,
        messageId: String?,
    ): MessageRecord {
        val id = messageId ?: UUID.randomUUID().toString()
        val message = MessageRecord(
            id = id,
            sessionId = sessionId,
            turnId = turnId,
            role = role,
            content = content,
            contentJson = contentJson,
            createdAt = Instant.now().toString(),
        )
        messages[id] = message
        return message
    }

    override fun updateMessageContent(messageId: String, content: String): MessageRecord? {
        val message = messages[messageId] ?: return null
        val updated = message.copy(content = content)
        messages[messageId] = updated
        return updated
    }

    override fun updateMessageContentJson(messageId: String, contentJson: String): MessageRecord? {
        val message = messages[messageId] ?: return null
        val updated = message.copy(contentJson = contentJson)
        messages[messageId] = updated
        return updated
    }

    override fun latestMessages(sessionId: String, limit: Int): List<MessageRecord> {
        return messages.values
            .filter { it.sessionId == sessionId }
            .sortedBy { it.createdAt }
            .takeLast(limit)
    }

    override fun appendEvent(
        sessionId: String,
        turnId: String?,
        messageId: String?,
        type: String,
        payloadJson: String,
    ): StoredEvent {
        return appendEvents(sessionId, listOf(NewEvent(turnId, messageId, type, payloadJson))).single()
    }

    override fun appendEvents(sessionId: String, events: List<NewEvent>): List<StoredEvent> {
        val session = sessions.getValue(sessionId)
        val base = session.lastSeq + 1
        val stored = events.mapIndexed { index, newEvent ->
            StoredEvent(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                seq = base + index,
                turnId = newEvent.turnId,
                messageId = newEvent.messageId,
                type = newEvent.type,
                payloadJson = newEvent.payloadJson,
                createdAt = Instant.now().toString(),
            )
        }
        eventLog += stored
        sessions[sessionId] = session.copy(
            lastSeq = base + events.size - 1,
            updatedAt = Instant.now().toString(),
        )
        return stored
    }

    fun events(): List<StoredEvent> = eventLog.toList()

    override fun listEvents(sessionId: String, afterSeq: Long, limit: Int): List<StoredEvent> {
        return eventLog.filter { it.sessionId == sessionId && it.seq > afterSeq }
            .sortedBy { it.seq }
            .take(limit)
    }

    override fun saveCheckpoint(turnId: String, sessionId: String, payloadJson: String): TurnCheckpoint {
        val now = Instant.now().toString()
        val existing = checkpoints[turnId]
        val checkpoint = TurnCheckpoint(
            turnId = turnId,
            sessionId = sessionId,
            payloadJson = payloadJson,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        checkpoints[turnId] = checkpoint
        return checkpoint
    }

    override fun findCheckpoint(turnId: String): TurnCheckpoint? = checkpoints[turnId]

    override fun deleteCheckpoint(turnId: String) {
        checkpoints.remove(turnId)
    }

    override fun markRunningTurnsInterrupted(): List<TurnRecord> {
        val running = turns.values.filter { it.status == TurnStatus.RUNNING }
        return running.map { updateTurnStatus(it.id, TurnStatus.INTERRUPTED)!! }
    }

    private companion object {
        val TERMINAL = setOf(
            TurnStatus.COMPLETED,
            TurnStatus.FAILED,
            TurnStatus.INTERRUPTED,
            TurnStatus.CANCELLED,
        )
    }
}
