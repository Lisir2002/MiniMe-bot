package com.minibot.control.model

import java.time.Instant
import java.util.UUID

class FakeModelStore : ModelStore {
    val credentials = mutableMapOf<String, ProviderCredential>()
    val definitions = mutableMapOf<String, ModelDefinition>()
    val bindings = mutableMapOf<String, ModelBinding>()
    val calls = mutableListOf<RecordedModelCall>()

    override fun createCredential(
        scope: CredentialScope,
        provider: String,
        baseUrl: String,
        encryptedSecret: ByteArray,
        secretNonce: ByteArray,
        keyVersion: Int,
    ): ProviderCredential {
        val credential = ProviderCredential(
            id = UUID.randomUUID().toString(),
            scope = scope,
            provider = provider,
            baseUrl = baseUrl,
            encryptedSecret = encryptedSecret,
            secretNonce = secretNonce,
            keyVersion = keyVersion,
            status = CredentialStatus.UNASSIGNED,
            createdAt = Instant.now().toString(),
            revokedAt = null,
        )
        credentials[credential.id] = credential
        return credential
    }

    override fun findCredential(credentialId: String): ProviderCredential? = credentials[credentialId]

    override fun listCredentials(): List<ProviderCredential> = credentials.values.toList()

    override fun updateCredentialStatus(
        credentialId: String,
        status: CredentialStatus,
    ): ProviderCredential? {
        val credential = credentials[credentialId] ?: return null
        val updated = credential.copy(
            status = status,
            revokedAt = if (status == CredentialStatus.REVOKED) Instant.now().toString() else credential.revokedAt,
        )
        credentials[credentialId] = updated
        return updated
    }

    override fun deleteCredential(credentialId: String) {
        credentials.remove(credentialId)
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
        val definition = ModelDefinition(
            id = UUID.randomUUID().toString(),
            provider = provider,
            model = model,
            displayName = displayName,
            capabilitiesJson = capabilitiesJson,
            contextWindow = contextWindow,
            inputPrice = inputPrice,
            outputPrice = outputPrice,
            createdAt = Instant.now().toString(),
        )
        definitions[definition.id] = definition
        return definition
    }

    override fun findDefinition(definitionId: String): ModelDefinition? = definitions[definitionId]

    override fun listDefinitions(): List<ModelDefinition> = definitions.values.toList()

    override fun createBinding(
        profileId: String,
        definitionId: String,
        credentialId: String,
    ): ModelBinding {
        val binding = ModelBinding(
            id = UUID.randomUUID().toString(),
            profileId = profileId,
            modelDefinitionId = definitionId,
            credentialId = credentialId,
            status = BindingStatus.ACTIVE.name,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        )
        bindings[binding.id] = binding
        return binding
    }

    override fun findBinding(bindingId: String): ModelBinding? = bindings[bindingId]

    override fun listBindings(profileId: String): List<ModelBinding> {
        return bindings.values.filter { it.profileId == profileId }
    }

    override fun updateBindingStatus(bindingId: String, status: BindingStatus): ModelBinding? {
        val binding = bindings[bindingId] ?: return null
        val updated = binding.copy(status = status.name, updatedAt = Instant.now().toString())
        bindings[bindingId] = updated
        return updated
    }

    override fun deleteBinding(bindingId: String) {
        bindings.remove(bindingId)
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
        calls += RecordedModelCall(
            sessionId = sessionId,
            turnId = turnId,
            bindingId = bindingId,
            provider = provider,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            latencyMs = latencyMs,
            status = status,
            errorCode = errorCode,
        )
    }
}

data class RecordedModelCall(
    val sessionId: String?,
    val turnId: String?,
    val bindingId: String?,
    val provider: String,
    val model: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long,
    val status: String,
    val errorCode: String?,
)
