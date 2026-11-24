package com.minibot.control.tool

import kotlinx.serialization.Serializable
enum class ToolRisk {
    SAFE,
    CONFIRM,
    DANGER,
}

data class ToolRequest(
    val callId: String,
    val turnId: String,
    val sessionId: String,
    val tool: String,
    val command: String,
    val cwd: String?,
    val timeoutMs: Long,
)

data class ToolResult(
    val callId: String,
    val exitCode: Int?,
    val durationMs: Long,
    val stdoutPreview: String,
    val stderrPreview: String,
    val truncated: Boolean,
    val outputRef: String?,
    val timedOut: Boolean = false,
    val denied: Boolean = false,
    val errorCode: String? = null,
    val approvalPending: Boolean = false,
    val approvalId: String? = null,
)

class ToolException(
    val code: String,
    message: String,
    val status: Int = 400,
) : RuntimeException(message)

class ApprovalException(
    val code: String,
    message: String,
    val status: Int = 400,
) : RuntimeException(message)

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
    CANCELLED,
}

@Serializable
data class ApprovalRecord(
    val id: String,
    val turnId: String,
    val sessionId: String,
    val status: ApprovalStatus,
    val requestJson: String,
    val decision: String?,
    val reason: String?,
    val expiresAt: String,
    val decidedAt: String?,
    val createdAt: String,
)

@Serializable
data class ApprovalDecisionResult(
    val approval: ApprovalRecord,
    val alreadyDecided: Boolean,
)

data class ToolApprovalRequest(
    val callId: String,
    val tool: String,
    val command: String,
    val risk: ToolRisk,
    val timeoutMs: Long,
)

interface ApprovalStore {
    fun create(turnId: String, sessionId: String, requestJson: String, expiresAt: String): ApprovalRecord
    fun find(approvalId: String): ApprovalRecord?
    fun listPending(): List<ApprovalRecord>
    fun decide(
        approvalId: String,
        status: ApprovalStatus,
        decision: String?,
        reason: String?,
    ): ApprovalRecord?
    fun markExpired(approvalId: String): ApprovalRecord?
}
