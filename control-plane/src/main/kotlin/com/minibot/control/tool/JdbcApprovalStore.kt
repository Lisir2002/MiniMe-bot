package com.minibot.control.tool

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcApprovalStore(private val dataSource: DataSource) : ApprovalStore {
    override fun create(
        turnId: String,
        sessionId: String,
        requestJson: String,
        expiresAt: String,
    ): ApprovalRecord {
        val id = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO approvals (id, turn_id, session_id, status, request_json, expires_at)
                VALUES (?, ?, ?, 'PENDING', ?::jsonb, ?::timestamptz)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(id))
                statement.setObject(2, UUID.fromString(turnId))
                statement.setObject(3, UUID.fromString(sessionId))
                statement.setString(4, requestJson)
                statement.setString(5, expiresAt)
                statement.executeUpdate()
            }
        }
        return find(id) ?: error("Approval insert did not persist")
    }

    override fun find(approvalId: String): ApprovalRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, turn_id, session_id, status, request_json::text, decision, reason,
                       expires_at, decided_at, created_at
                FROM approvals
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(approvalId))
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toApproval() else null
                }
            }
        }
    }

    override fun listPending(): List<ApprovalRecord> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, turn_id, session_id, status, request_json::text, decision, reason,
                       expires_at, decided_at, created_at
                FROM approvals
                WHERE status = 'PENDING'
                ORDER BY created_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val approvals = mutableListOf<ApprovalRecord>()
                    while (result.next()) approvals += result.toApproval()
                    return approvals
                }
            }
        }
    }

    override fun decide(
        approvalId: String,
        status: ApprovalStatus,
        decision: String?,
        reason: String?,
    ): ApprovalRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE approvals
                SET status = ?, decision = ?, reason = ?, decided_at = now()
                WHERE id = ? AND status = 'PENDING'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, decision)
                statement.setString(3, reason)
                statement.setObject(4, UUID.fromString(approvalId))
                statement.executeUpdate()
            }
        }
        return find(approvalId)
    }

    override fun markExpired(approvalId: String): ApprovalRecord? {
        return decide(approvalId, ApprovalStatus.EXPIRED, "expired", "Approval expired")
    }

    private fun ResultSet.toApproval(): ApprovalRecord {
        return ApprovalRecord(
            id = getObject("id").toString(),
            turnId = getObject("turn_id").toString(),
            sessionId = getObject("session_id").toString(),
            status = ApprovalStatus.valueOf(getString("status")),
            requestJson = getString("request_json") ?: "{}",
            decision = getString("decision"),
            reason = getString("reason"),
            expiresAt = getTimestamp("expires_at").toInstant().toString(),
            decidedAt = getTimestamp("decided_at")?.toInstant()?.toString(),
            createdAt = getTimestamp("created_at").toInstant().toString(),
        )
    }
}
