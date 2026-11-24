package com.minibot.control.session

interface SessionStore {
    fun ensureDefaultWorkspace(profileId: String): String

    fun createSession(
        profileId: String,
        workspaceId: String?,
        title: String,
        modelBindingId: String? = null,
    ): SessionRecord

    fun listSessions(): List<SessionRecord>

    fun findSession(sessionId: String): SessionRecord?

    fun findTurn(turnId: String): TurnRecord?

    fun findTurnByClientMessageId(sessionId: String, clientMessageId: String): TurnRecord?

    fun createTurn(
        sessionId: String,
        clientMessageId: String,
        content: String,
        contentJson: String,
    ): TurnCreation

    fun listQueuedTurns(): List<TurnRecord>

    fun listTurns(sessionId: String): List<TurnRecord>

    fun updateTurnStatus(
        turnId: String,
        status: TurnStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): TurnRecord?

    fun cancelQueuedTurn(sessionId: String, turnId: String): TurnRecord?

    fun createMessage(
        sessionId: String,
        turnId: String?,
        role: String,
        content: String,
        contentJson: String,
        messageId: String? = null,
    ): MessageRecord

    fun updateMessageContent(messageId: String, content: String): MessageRecord?

    fun updateMessageContentJson(messageId: String, contentJson: String): MessageRecord?

    fun latestMessages(sessionId: String, limit: Int): List<MessageRecord>

    fun appendEvent(
        sessionId: String,
        turnId: String?,
        messageId: String?,
        type: String,
        payloadJson: String,
    ): StoredEvent

    fun appendEvents(
        sessionId: String,
        events: List<NewEvent>,
    ): List<StoredEvent>

    fun listEvents(sessionId: String, afterSeq: Long, limit: Int): List<StoredEvent>

    fun markRunningTurnsInterrupted(): List<TurnRecord>

    fun saveCheckpoint(turnId: String, sessionId: String, payloadJson: String): TurnCheckpoint

    fun findCheckpoint(turnId: String): TurnCheckpoint?

    fun deleteCheckpoint(turnId: String)
}
