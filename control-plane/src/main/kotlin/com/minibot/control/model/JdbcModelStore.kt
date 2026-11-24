package com.minibot.control.model

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcModelStore(private val dataSource: DataSource) : ModelStore {
    override fun createCredential(
        scope: CredentialScope,
        provider: String,
        baseUrl: String,
        encryptedSecret: ByteArray,
        secretNonce: ByteArray,
        keyVersion: Int,
    ): ProviderCredential {
        val id = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO provider_credentials (
                    id, scope, provider, base_url, encrypted_secret, secret_nonce, key_version, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'UNASSIGNED')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(id))
                statement.setString(2, scope.name)
                statement.setString(3, provider)
                statement.setString(4, baseUrl)
                statement.setBytes(5, encryptedSecret)
                statement.setBytes(6, secretNonce)
                statement.setInt(7, keyVersion)
                statement.executeUpdate()
            }
        }
        return findCredential(id) ?: error("Credential insert did not persist")
    }

    override fun findCredential(credentialId: String): ProviderCredential? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, scope, provider, base_url, encrypted_secret, secret_nonce,
                       key_version, status, created_at, revoked_at
                FROM provider_credentials
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(credentialId))
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toCredential() else null
                }
            }
        }
    }

    override fun listCredentials(): List<ProviderCredential> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, scope, provider, base_url, encrypted_secret, secret_nonce,
                       key_version, status, created_at, revoked_at
                FROM provider_credentials
                WHERE revoked_at IS NULL
                ORDER BY created_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val credentials = mutableListOf<ProviderCredential>()
                    while (result.next()) credentials += result.toCredential()
                    return credentials
                }
            }
        }
    }

    override fun updateCredentialStatus(
        credentialId: String,
        status: CredentialStatus,
    ): ProviderCredential? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE provider_credentials
                SET status = ?,
                    revoked_at = CASE WHEN ? = 'REVOKED' THEN now() ELSE revoked_at END
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, status.name)
                statement.setObject(3, UUID.fromString(credentialId))
                statement.executeUpdate()
            }
        }
        return findCredential(credentialId)
    }

    override fun deleteCredential(credentialId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM provider_credentials WHERE id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(credentialId))
                statement.executeUpdate()
            }
        }
    }

    override fun createDefinition(
        provider: String,
        model: String,
        displayName: String,
        capabilitiesJson: String,
        contextWindow: Int,
        inputPrice: Double,
        outputPrice: Double,
    ): ModelDefinition {
        val id = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO model_definitions (
                    id, provider, model, display_name, capabilities_json, context_window,
                    input_price, output_price
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(id))
                statement.setString(2, provider)
                statement.setString(3, model)
                statement.setString(4, displayName)
                statement.setString(5, capabilitiesJson)
                statement.setInt(6, contextWindow)
                statement.setBigDecimal(7, java.math.BigDecimal.valueOf(inputPrice))
                statement.setBigDecimal(8, java.math.BigDecimal.valueOf(outputPrice))
                statement.executeUpdate()
            }
        }
        return findDefinition(id) ?: error("Model definition insert did not persist")
    }

    override fun findDefinition(definitionId: String): ModelDefinition? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, provider, model, display_name, capabilities_json, context_window,
                       input_price, output_price, created_at
                FROM model_definitions
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(definitionId))
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toDefinition() else null
                }
            }
        }
    }

    override fun listDefinitions(): List<ModelDefinition> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, provider, model, display_name, capabilities_json, context_window,
                       input_price, output_price, created_at
                FROM model_definitions
                ORDER BY created_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val definitions = mutableListOf<ModelDefinition>()
                    while (result.next()) definitions += result.toDefinition()
                    return definitions
                }
            }
        }
    }

    override fun createBinding(
        profileId: String,
        definitionId: String,
        credentialId: String,
    ): ModelBinding {
        val id = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO model_bindings (
                    id, profile_id, model_definition_id, credential_id, status
                ) VALUES (?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(id))
                statement.setObject(2, UUID.fromString(profileId))
                statement.setObject(3, UUID.fromString(definitionId))
                statement.setObject(4, UUID.fromString(credentialId))
                statement.executeUpdate()
            }
        }
        return findBinding(id) ?: error("Model binding insert did not persist")
    }

    override fun findBinding(bindingId: String): ModelBinding? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, profile_id, model_definition_id, credential_id, status, created_at, updated_at
                FROM model_bindings
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(bindingId))
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toBinding() else null
                }
            }
        }
    }

    override fun listBindings(profileId: String): List<ModelBinding> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, profile_id, model_definition_id, credential_id, status, created_at, updated_at
                FROM model_bindings
                WHERE profile_id = ?
                ORDER BY created_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(profileId))
                statement.executeQuery().use { result ->
                    val bindings = mutableListOf<ModelBinding>()
                    while (result.next()) bindings += result.toBinding()
                    return bindings
                }
            }
        }
    }

    override fun updateBindingStatus(bindingId: String, status: BindingStatus): ModelBinding? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE model_bindings SET status = ?, updated_at = now() WHERE id = ?",
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setObject(2, UUID.fromString(bindingId))
                statement.executeUpdate()
            }
        }
        return findBinding(bindingId)
    }

    override fun deleteBinding(bindingId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM model_bindings WHERE id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(bindingId))
                statement.executeUpdate()
            }
        }
    }

    override fun recordCall(
        sessionId: String?,
        turnId: String?,
        bindingId: String?,
        provider: String,
        model: String,
        inputTokens: Int?,
        outputTokens: Int?,
        latencyMs: Long,
        status: String,
        errorCode: String?,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO model_calls (
                    session_id, turn_id, binding_id, provider, model,
                    input_tokens, output_tokens, latency_ms, status, error_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId?.let(UUID::fromString))
                statement.setObject(2, turnId?.let(UUID::fromString))
                statement.setObject(3, bindingId?.let(UUID::fromString))
                statement.setString(4, provider)
                statement.setString(5, model)
                statement.setObject(6, inputTokens, java.sql.Types.INTEGER)
                statement.setObject(7, outputTokens, java.sql.Types.INTEGER)
                statement.setLong(8, latencyMs)
                statement.setString(9, status)
                statement.setString(10, errorCode)
                statement.executeUpdate()
            }
        }
    }

    private fun ResultSet.toCredential(): ProviderCredential {
        return ProviderCredential(
            id = getObject("id").toString(),
            scope = CredentialScope.valueOf(getString("scope")),
            provider = getString("provider"),
            baseUrl = getString("base_url"),
            encryptedSecret = getBytes("encrypted_secret"),
            secretNonce = getBytes("secret_nonce"),
            keyVersion = getInt("key_version"),
            status = CredentialStatus.valueOf(getString("status")),
            createdAt = getTimestamp("created_at").toInstant().toString(),
            revokedAt = getTimestamp("revoked_at")?.toInstant()?.toString(),
        )
    }

    private fun ResultSet.toDefinition(): ModelDefinition {
        return ModelDefinition(
            id = getObject("id").toString(),
            provider = getString("provider"),
            model = getString("model"),
            displayName = getString("display_name"),
            capabilitiesJson = getString("capabilities_json") ?: "[]",
            contextWindow = getInt("context_window"),
            inputPrice = getBigDecimal("input_price").toDouble(),
            outputPrice = getBigDecimal("output_price").toDouble(),
            createdAt = getTimestamp("created_at").toInstant().toString(),
        )
    }

    private fun ResultSet.toBinding(): ModelBinding {
        return ModelBinding(
            id = getObject("id").toString(),
            profileId = getObject("profile_id").toString(),
            modelDefinitionId = getObject("model_definition_id").toString(),
            credentialId = getObject("credential_id").toString(),
            status = getString("status"),
            createdAt = getTimestamp("created_at").toInstant().toString(),
            updatedAt = getTimestamp("updated_at").toInstant().toString(),
        )
    }
}
