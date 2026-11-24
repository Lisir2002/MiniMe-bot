package com.minibot.control.pairing

import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPairingStore(private val dataSource: DataSource) : PairingStore {
    override fun ensureServer(serverName: String, timezone: String): String {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT server_id FROM server_settings ORDER BY created_at LIMIT 1",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        return result.getString("server_id")
                    }
                }
            }

            val serverId = UUID.randomUUID().toString()
            connection.prepareStatement(
                """
                INSERT INTO server_settings (server_id, server_name, timezone)
                VALUES (?, ?, ?)
                ON CONFLICT (server_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, serverId)
                statement.setString(2, serverName)
                statement.setString(3, timezone)
                statement.executeUpdate()
            }
            return serverId
        }
    }

    override fun seedPairingToken(tokenHash: String, expiresAt: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO pairing_tokens (token_hash, expires_at)
                VALUES (?, ?)
                ON CONFLICT (token_hash) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.setTimestamp(2, java.sql.Timestamp.from(expiresAt))
                statement.executeUpdate()
            }
        }
    }

    override fun activeDevice(): DeviceRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, public_key, token_hash, device_name, created_at
                FROM server_device
                WHERE revoked_at IS NULL
                ORDER BY created_at
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toDevice() else null
                }
            }
        }
    }

    override fun claimDevice(
        device: DeviceRecord,
        tokenHash: String,
        now: Instant,
    ): ClaimStoreResult {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "SELECT 1 FROM server_device WHERE revoked_at IS NULL LIMIT 1",
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        if (result.next()) {
                            connection.rollback()
                            return ClaimStoreResult.ALREADY_PAIRED
                        }
                    }
                }

                val tokenExpiresAt: Instant
                val tokenUsedAt: Instant?
                connection.prepareStatement(
                    "SELECT expires_at, used_at FROM pairing_tokens WHERE token_hash = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, tokenHash)
                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            connection.rollback()
                            return ClaimStoreResult.TOKEN_INVALID
                        }
                        tokenExpiresAt = result.getTimestamp("expires_at").toInstant()
                        tokenUsedAt = result.getTimestamp("used_at")?.toInstant()
                    }
                }

                if (tokenUsedAt != null || !tokenExpiresAt.isAfter(now)) {
                    connection.rollback()
                    return ClaimStoreResult.TOKEN_INVALID
                }

                connection.prepareStatement(
                    """
                    INSERT INTO server_device (id, public_key, token_hash, device_name, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(device.id))
                    statement.setString(2, device.publicKey)
                    statement.setString(3, device.tokenHash)
                    statement.setString(4, device.deviceName)
                    statement.setTimestamp(5, java.sql.Timestamp.from(device.createdAt))
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    "UPDATE pairing_tokens SET used_at = ? WHERE token_hash = ?",
                ).use { statement ->
                    statement.setTimestamp(1, java.sql.Timestamp.from(now))
                    statement.setString(2, tokenHash)
                    statement.executeUpdate()
                }

                connection.commit()
                return ClaimStoreResult.OK
            } catch (error: SQLException) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun findDeviceByTokenHash(tokenHash: String): DeviceRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, public_key, token_hash, device_name, created_at
                FROM server_device
                WHERE token_hash = ? AND revoked_at IS NULL
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toDevice() else null
                }
            }
        }
    }

    private fun ResultSet.toDevice(): DeviceRecord {
        return DeviceRecord(
            id = getObject("id").toString(),
            publicKey = getString("public_key"),
            tokenHash = getString("token_hash"),
            deviceName = getString("device_name"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
    }
}
