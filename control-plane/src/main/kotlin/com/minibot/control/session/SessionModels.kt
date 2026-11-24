package com.minibot.control.session

import com.minibot.control.container.ContainerSummary
import kotlinx.serialization.Serializable
import java.time.Instant

enum class TurnStatus {
    QUEUED,
    WAITING_RUNTIME,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    CANCELLED,
}

@Serializable
data class SessionRecord(
    val id: String,
    val profileId: String,
    val workspaceId: String?,
    val title: String,
    val modelBindingId: String?,
    val lastSeq: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class TurnRecord(
    val id: String,
    val sessionId: String,
    val clientMessageId: String?,
    val status: TurnStatus,
    val queueOrder: Long,
    val createdSeq: Long?,
    val createdAt: String,
    val startedAt: String?,
    val finishedAt: String?,
    val errorCode: String?,
    val errorMessage: String?,
)

@Serializable
data class MessageRecord(
    val id: String,
    val sessionId: String,
    val turnId: String?,
    val role: String,
    val content: String,
    val contentJson: String,
    val createdAt: String,
)

@Serializable
data class StoredEvent(
    val id: String,
    val sessionId: String,
    val seq: Long,
    val turnId: String?,
    val messageId: String?,
    val type: String,
    val payloadJson: String,
    val createdAt: String,
)

data class TurnCheckpoint(
    val turnId: String,
    val sessionId: String,
    val payloadJson: String,
    val createdAt: String,
    val updatedAt: String,
)

class TurnSuspended(
    val turnId: String,
    val approvalId: String?,
) : RuntimeException("Turn is waiting for approval")

data class NewEvent(
    val turnId: String?,
    val messageId: String?,
    val type: String,
    val payloadJson: String,
)

data class TurnCreation(
    val turn: TurnRecord,
    val userMessage: MessageRecord?,
    val events: List<StoredEvent>,
)

@Serializable
data class SessionSnapshot(
    val session: SessionRecord,
    val currentTurn: TurnRecord?,
    val queue: List<TurnRecord>,
    val messages: List<MessageRecord>,
    val lastSeq: Long,
    val pendingApprovals: List<String>,
    val container: ContainerSummary?,
    val modelBindingId: String?,
)

@Serializable
data class CreateSessionRequest(
    val profileId: String,
    val title: String,
    val workspaceId: String? = null,
    val modelBindingId: String? = null,
)

@Serializable
data class CreateTurnRequest(
    val clientMessageId: String,
    val content: String,
    val attachments: List<String> = emptyList(),
)

data class SessionSummary(
    val id: String,
    val profileId: String,
    val workspaceId: String?,
    val title: String,
    val lastSeq: Long,
    val createdAt: String,
    val updatedAt: String,
)

fun SessionRecord.toSummary(): SessionSummary = SessionSummary(
    id = id,
    profileId = profileId,
    workspaceId = workspaceId,
    title = title,
    lastSeq = lastSeq,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun nowString(): String = Instant.now().toString()
