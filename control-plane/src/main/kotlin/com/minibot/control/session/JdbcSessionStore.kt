package com.minibot.control.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcSessionStore(private val dataSource: DataSource) : SessionStore {
    override fun ensureDefaultWorkspace(profileId: String): String {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO workspaces (profile_id, name, root_path)
                VALUES (?, 'default', '/workspace')
                ON CONFLICT (profile_id, name) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(profileId))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "SELECT id FROM workspaces WHERE profile_id = ? AND name = 'default'",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(profileId))
                statement.executeQuery().use { result ->
                    if (result.next()) return result.getObject(1).toString()
                }
            }
        }
        throw IllegalStateException("Failed to create default workspace for profile $profileId")
    }

    override fun createSession(
        profileId: String,
        workspaceId: String?,
        title: String,
        modelBindingId: String?,
    ): SessionRecord {
        val sessionId = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO sessions (id, profile_id, workspace_id, title, model_binding_id)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(sessionId))
                statement.setObject(2, UUID.fromString(profileId))
                statement.setObject(3, workspaceId?.let(UUID::fromString))
                statement.setString(4, title)
                statement.setString(5, modelBindingId)
                statement.executeUpdate()
            }
        }
        return findSession(sessionId) ?: error("Session insert did not persist")
    }

    override fun listSessions(): List<SessionRecord> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, profile_id, workspace_id, title, model_binding_id, last_seq, created_at, updated_at
                FROM sessions
                ORDER BY updated_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val sessions = mutableListOf<SessionRecord>()
                    while (result.next()) sessions += result.toSession()
                    return sessions
                }
            }
        }
    }

    override fun findSession(sessionId: String): SessionRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, profile_id, workspace_id, title, model_binding_id, last_seq, created_at, updated_at
                FROM sessions
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(sessionId))
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toSession() else null
                }
            }
        }
    }

    override fun findTurn(turnId: String): TurnRecord? {
        dataSource.connection.use { connection -> return selectTurn(connection, turnId) }
    }

    override fun findTurnByClientMessageId(sessionId: String, clientMessageId: String): TurnRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, client_message_id, status, queue_order, created_seq,
                       created_at, started_at, finished_at, error_code, error_message
                FROM turns
                WHERE session_id = ? AND client_message_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(sessionId))
                statement.setString(2, clientMessageId)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toTurn() else null
                }
            }
        }
    }

    override fun createTurn(
        sessionId: String,
        clientMessageId: String,
        content: String,
        contentJson: String,
    ): TurnCreation {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val existing = selectTurnByClientMessageId(connection, sessionId, clientMessageId)
                if (existing != null) {
                    connection.commit()
                    return TurnCreation(existing, null, emptyList())
                }

                val turnId = UUID.randomUUID().toString()
                val userMessageId = UUID.randomUUID().toString()
                val lastSeq = connection.prepareStatement(
                    "UPDATE sessions SET last_seq = last_seq + 2, updated_at = now() WHERE id = ? RETURNING last_seq",
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(sessionId))
                    statement.executeQuery().use { result ->
                        if (!result.next()) error("Session not found: $sessionId")
                        result.getLong(1)
                    }
                }
                val createdSeq = lastSeq - 1
                val messageSeq = lastSeq

                connection.prepareStatement(
                    """
                    INSERT INTO turns (
                        id, session_id, client_message_id, status, created_seq
                    ) VALUES (?, ?, ?, 'QUEUED', ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(turnId))
                    statement.setObject(2, UUID.fromString(sessionId))
                    statement.setString(3, clientMessageId)
                    statement.setLong(4, createdSeq)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO messages (id, session_id, turn_id, role, content, content_json)
                    VALUES (?, ?, ?, 'user', ?, ?::jsonb)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(userMessageId))
                    statement.setObject(2, UUID.fromString(sessionId))
                    statement.setObject(3, UUID.fromString(turnId))
                    statement.setString(4, content)
                    statement.setString(5, contentJson)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    "INSERT INTO message_search (message_id, session_id, content) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(userMessageId))
                    statement.setObject(2, UUID.fromString(sessionId))
                    statement.setString(3, content)
                    statement.executeUpdate()
                }

                val turnEvent = insertEvent(
                    connection = connection,
                    sessionId = sessionId,
                    seq = createdSeq,
                    turnId = turnId,
                    messageId = null,
                    type = "turn.created",
                    payloadJson = buildJsonObject {
                        put("clientMessageId", clientMessageId)
                        put("status", TurnStatus.QUEUED.name)
                    }.toString(),
                )
                val messageEvent = insertEvent(
                    connection = connection,
                    sessionId = sessionId,
                    seq = messageSeq,
                    turnId = turnId,
                    messageId = userMessageId,
                    type = "message.completed",
                    payloadJson = buildJsonObject {
                        put("messageId", userMessageId)
                        put("role", "user")
                        put("content", content)
                    }.toString(),
                )

                val turn = selectTurn(connection, turnId) ?: error("Turn insert did not persist")
                val message = selectMessage(connection, userMessageId) ?: error("Message insert did not persist")
                connection.commit()
                return TurnCreation(turn, message, listOf(turnEvent, messageEvent))
            } catch (error: Exception) {
                runCatching { connection.rollback() }
                throw error
            } finally {
                runCatching { connection.autoCommit = true }
            }
        }
    }

    override fun listQueuedTurns(): List<TurnRecord> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, client_message_id, status, queue_order, created_seq,
                       created_at, started_at, finished_at, error_code, error_message
                FROM turns
                WHERE status = 'QUEUED'
                ORDER BY queue_order ASC
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val turns = mutableListOf<TurnRecord>()
                    while (result.next()) turns += result.toTurn()
                    return turns
                }
            }
        }
    }

    override fun listTurns(sessionId: String): List<TurnRecord> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, client_message_id, status, queue_order, created_seq,
                       created_at, started_at, finished_at, error_code, error_message
                FROM turns
                WHERE session_id = ?
                ORDER BY queue_order ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(sessionId))
                statement.executeQuery().use { result ->
                    val turns = mutableListOf<TurnRecord>()
                    while (result.next()) turns += result.toTurn()
                    return turns
                }
            }
        }
    }

    override fun updateTurnStatus(
        turnId: String,
        status: TurnStatus,
        errorCode: String?,
        errorMessage: String?,
    ): TurnRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE turns
                SET status = ?,
                    started_at = CASE
                        WHEN ? = 'RUNNING' AND started_at IS NULL THEN now()
                        ELSE started_at
                    END,
                    finished_at = CASE
                        WHEN ? IN ('COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELLED') THEN now()
                        ELSE finished_at
                    END,
                    error_code = ?,
                    error_message = ?
                WHERE id = ?
                RETURNING id, session_id, client_message_id, status, queue_order, created_seq,
                          created_at, started_at, finished_at, error_code, error_message
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, status.name)
                statement.setString(3, status.name)
                statement.setString(4, errorCode)
                statement.setString(5, errorMessage)
                statement.setObject(6, UUID.fromString(turnId))
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toTurn() else null
                }
            }
        }
    }

    override fun cancelQueuedTurn(sessionId: String, turnId: String): TurnRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE turns
                SET status = 'CANCELLED', finished_at = now()
                WHERE id = ? AND session_id = ? AND status IN ('QUEUED', 'WAITING_RUNTIME')
                RETURNING id, session_id, client_message_id, status, queue_order, created_seq,
                          created_at, started_at, finished_at, error_code, error_message
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(turnId))
                statement.setObject(2, UUID.fromString(sessionId))
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toTurn() else null
                }
            }
        }
    }

    override fun createMessage(
        sessionId: String,
        turnId: String?,
        role: String,
        content: String,
        contentJson: String,
        messageId: String?,
    ): MessageRecord {
        val id = messageId?.let(UUID::fromString) ?: UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO messages (id, session_id, turn_id, role, content, content_json)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.fromString(sessionId))
                statement.setObject(3, turnId?.let(UUID::fromString))
                statement.setString(4, role)
                statement.setString(5, content)
                statement.setString(6, contentJson)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO message_search (message_id, session_id, content) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.fromString(sessionId))
                statement.setString(3, content)
                statement.executeUpdate()
            }
        }
        return findMessage(id.toString()) ?: error("Message insert did not persist")
    }

    override fun updateMessageContent(messageId: String, content: String): MessageRecord? {
        val id = UUID.fromString(messageId)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE messages SET content = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, content)
                statement.setObject(2, id)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE message_search SET content = ? WHERE message_id = ?",
            ).use { statement ->
                statement.setString(1, content)
                statement.setObject(2, id)
                statement.executeUpdate()
            }
        }
        return findMessage(messageId)
    }

    override fun updateMessageContentJson(messageId: String, contentJson: String): MessageRecord? {
        val id = UUID.fromString(messageId)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE messages SET content_json = ?::jsonb WHERE id = ?",
            ).use { statement ->
                statement.setString(1, contentJson)
                statement.setObject(2, id)
                statement.executeUpdate()
            }
        }
        return findMessage(messageId)
    }

    override fun latestMessages(sessionId: String, limit: Int): List<MessageRecord> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, turn_id, role, content, content_json, created_at
                FROM messages
                WHERE session_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(sessionId))
                statement.setInt(2, limit)
                statement.executeQuery().use { result ->
                    val messages = mutableListOf<MessageRecord>()
                    while (result.next()) messages += result.toMessage()
                    return messages.reversed()
                }
            }
        }
    }

    override fun appendEvent(
        sessionId: String,
        turnId: String?,
        messageId: String?,
        type: String,
        payloadJson: String,
    ): StoredEvent {
        return appendEvents(
            sessionId,
            listOf(NewEvent(turnId, messageId, type, payloadJson)),
        ).single()
    }

    override fun appendEvents(sessionId: String, events: List<NewEvent>): List<StoredEvent> {
        if (events.isEmpty()) return emptyList()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val count = events.size
                val lastSeq = connection.prepareStatement(
                    "UPDATE sessions SET last_seq = last_seq + ?, updated_at = now() WHERE id = ? RETURNING last_seq",
                ).use { statement ->
                    statement.setInt(1, count)
                    statement.setObject(2, UUID.fromString(sessionId))
                    statement.executeQuery().use { result ->
                        if (!result.next()) error("Session not found: $sessionId")
                        result.getLong(1)
                    }
                }
                val base = lastSeq - count + 1
                val stored = events.mapIndexed { index, event ->
                    insertEvent(
                        connection = connection,
                        sessionId = sessionId,
                        seq = base + index,
                        turnId = event.turnId,
                        messageId = event.messageId,
                        type = event.type,
                        payloadJson = event.payloadJson,
                    )
                }
                connection.commit()
                return stored
            } catch (error: Exception) {
                runCatching { connection.rollback() }
                throw error
            } finally {
                runCatching { connection.autoCommit = true }
            }
        }
    }

    override fun listEvents(sessionId: String, afterSeq: Long, limit: Int): List<StoredEvent> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, seq, turn_id, message_id, type, payload_json, created_at
                FROM events
                WHERE session_id = ? AND seq > ?
                ORDER BY seq ASC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(sessionId))
                statement.setLong(2, afterSeq)
                statement.setInt(3, limit)
                statement.executeQuery().use { result ->
                    val events = mutableListOf<StoredEvent>()
                    while (result.next()) events += result.toEvent()
                    return events
                }
            }
        }
    }

    override fun markRunningTurnsInterrupted(): List<TurnRecord> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE turns
                SET status = 'INTERRUPTED', finished_at = now()
                WHERE status IN ('RUNNING', 'WAITING_RUNTIME')
                RETURNING id, session_id, client_message_id, status, queue_order, created_seq,
                          created_at, started_at, finished_at, error_code, error_message
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val turns = mutableListOf<TurnRecord>()
                    while (result.next()) turns += result.toTurn()
                    return turns
                }
            }
        }
    }

    override fun saveCheckpoint(turnId: String, sessionId: String, payloadJson: String): TurnCheckpoint {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO turn_checkpoints (turn_id, session_id, payload_json)
                VALUES (?, ?, ?::jsonb)
                ON CONFLICT (turn_id) DO UPDATE
                SET payload_json = EXCLUDED.payload_json, updated_at = now()
                RETURNING turn_id, session_id, payload_json::text, created_at, updated_at
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(turnId))
                statement.setObject(2, UUID.fromString(sessionId))
                statement.setString(3, payloadJson)
                statement.executeQuery().use { result ->
                    if (!result.next()) error("Checkpoint insert did not persist")
                    return TurnCheckpoint(
                        turnId = result.getObject("turn_id").toString(),
                        sessionId = result.getObject("session_id").toString(),
                        payloadJson = result.getString("payload_json"),
                        createdAt = result.getTimestamp("created_at").toInstant().toString(),
                        updatedAt = result.getTimestamp("updated_at").toInstant().toString(),
                    )
                }
            }
        }
    }

    override fun findCheckpoint(turnId: String): TurnCheckpoint? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT turn_id, session_id, payload_json::text, created_at, updated_at
                FROM turn_checkpoints
                WHERE turn_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(turnId))
                statement.executeQuery().use { result ->
                    if (!result.next()) return null
                    return TurnCheckpoint(
                        turnId = result.getObject("turn_id").toString(),
                        sessionId = result.getObject("session_id").toString(),
                        payloadJson = result.getString("payload_json"),
                        createdAt = result.getTimestamp("created_at").toInstant().toString(),
                        updatedAt = result.getTimestamp("updated_at").toInstant().toString(),
                    )
                }
            }
        }
    }

    override fun deleteCheckpoint(turnId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM turn_checkpoints WHERE turn_id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(turnId))
                statement.executeUpdate()
            }
        }
    }

    private fun selectTurn(connection: Connection, turnId: String): TurnRecord? {
        connection.prepareStatement(
            """
            SELECT id, session_id, client_message_id, status, queue_order, created_seq,
                   created_at, started_at, finished_at, error_code, error_message
            FROM turns
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(turnId))
            statement.executeQuery().use { result ->
                return if (result.next()) result.toTurn() else null
            }
        }
    }

    private fun selectTurnByClientMessageId(
        connection: Connection,
        sessionId: String,
        clientMessageId: String,
    ): TurnRecord? {
        connection.prepareStatement(
            """
            SELECT id, session_id, client_message_id, status, queue_order, created_seq,
                   created_at, started_at, finished_at, error_code, error_message
            FROM turns
            WHERE session_id = ? AND client_message_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(sessionId))
            statement.setString(2, clientMessageId)
            statement.executeQuery().use { result ->
                return if (result.next()) result.toTurn() else null
            }
        }
    }

    private fun findMessage(messageId: String): MessageRecord? {
        dataSource.connection.use { connection -> return selectMessage(connection, messageId) }
    }

    private fun selectMessage(connection: Connection, messageId: String): MessageRecord? {
        connection.prepareStatement(
            """
            SELECT id, session_id, turn_id, role, content, content_json, created_at
            FROM messages
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(messageId))
            statement.executeQuery().use { result ->
                return if (result.next()) result.toMessage() else null
            }
        }
    }

    private fun insertEvent(
        connection: Connection,
        sessionId: String,
        seq: Long,
        turnId: String?,
        messageId: String?,
        type: String,
        payloadJson: String,
    ): StoredEvent {
        val eventId = UUID.randomUUID().toString()
        connection.prepareStatement(
            """
                INSERT INTO events (id, session_id, seq, turn_id, message_id, type, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(eventId))
            statement.setObject(2, UUID.fromString(sessionId))
            statement.setLong(3, seq)
            statement.setObject(4, turnId?.let(UUID::fromString))
            statement.setObject(5, messageId?.let(UUID::fromString))
            statement.setString(6, type)
            statement.setString(7, payloadJson)
            statement.executeUpdate()
        }
        return StoredEvent(
            id = eventId,
            sessionId = sessionId,
            seq = seq,
            turnId = turnId,
            messageId = messageId,
            type = type,
            payloadJson = payloadJson,
            createdAt = Instant.now().toString(),
        )
    }

    private fun ResultSet.toSession(): SessionRecord {
        return SessionRecord(
            id = getObject("id").toString(),
            profileId = getObject("profile_id").toString(),
            workspaceId = getObject("workspace_id")?.toString(),
            title = getString("title"),
            modelBindingId = getString("model_binding_id"),
            lastSeq = getLong("last_seq"),
            createdAt = getTimestamp("created_at").toInstant().toString(),
            updatedAt = getTimestamp("updated_at").toInstant().toString(),
        )
    }

    private fun ResultSet.toTurn(): TurnRecord {
        return TurnRecord(
            id = getObject("id").toString(),
            sessionId = getObject("session_id").toString(),
            clientMessageId = getString("client_message_id"),
            status = TurnStatus.valueOf(getString("status")),
            queueOrder = getLong("queue_order"),
            createdSeq = nullableLong("created_seq"),
            createdAt = getTimestamp("created_at").toInstant().toString(),
            startedAt = nullableTimestamp("started_at"),
            finishedAt = nullableTimestamp("finished_at"),
            errorCode = getString("error_code"),
            errorMessage = getString("error_message"),
        )
    }

    private fun ResultSet.toMessage(): MessageRecord {
        return MessageRecord(
            id = getObject("id").toString(),
            sessionId = getObject("session_id").toString(),
            turnId = getObject("turn_id")?.toString(),
            role = getString("role"),
            content = getString("content"),
            contentJson = getString("content_json") ?: "{}",
            createdAt = getTimestamp("created_at").toInstant().toString(),
        )
    }

    private fun ResultSet.toEvent(): StoredEvent {
        return StoredEvent(
            id = getObject("id").toString(),
            sessionId = getObject("session_id").toString(),
            seq = getLong("seq"),
            turnId = getObject("turn_id")?.toString(),
            messageId = getObject("message_id")?.toString(),
            type = getString("type"),
            payloadJson = getString("payload_json") ?: "{}",
            createdAt = getTimestamp("created_at").toInstant().toString(),
        )
    }

    private fun ResultSet.nullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun ResultSet.nullableTimestamp(column: String): String? {
        val value: Timestamp? = getTimestamp(column)
        return value?.toInstant()?.toString()
    }
}
