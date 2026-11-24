package com.minibot.control.model

import com.minibot.control.container.ContainerException
import com.minibot.control.container.ContainerService
import com.minibot.control.security.EncryptedSecret
import com.minibot.control.security.SecretVault
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.net.URI
import java.util.UUID

class ModelService(
    private val store: ModelStore,
    private val vault: SecretVault,
    private val containers: ContainerService,
) {
    fun createCredential(request: CreateCredentialRequest): CredentialSummary {
        val provider = request.provider.trim().lowercase()
        val baseUrl = request.baseUrl.trim().trimEnd('/')
        val secret = request.secret.trim()
        if (provider.length !in 1..64) throw badRequest("invalid_provider", "Provider is required")
        if (baseUrl.length > 512) throw badRequest("invalid_base_url", "baseUrl is too long")
        val baseUri = runCatching { URI.create(baseUrl) }.getOrNull()
        if (baseUri == null || baseUri.scheme != "https" || baseUri.host.isNullOrBlank()) {
            throw badRequest("invalid_base_url", "baseUrl must be an https URL")
        }
        if (secret.isBlank() || secret.length > 8_192) {
            throw badRequest("invalid_secret", "A secret is required")
        }

        val encrypted = vault.encrypt(secret)
        val credential = store.createCredential(
            scope = CredentialScope.CLOUD_SERVER,
            provider = provider,
            baseUrl = baseUrl,
            encryptedSecret = encrypted.ciphertext,
            secretNonce = encrypted.nonce,
            keyVersion = encrypted.keyVersion,
        )
        return credential.toSummary()
    }

    fun listCredentials(): List<CredentialSummary> = store.listCredentials().map { it.toSummary() }

    fun createDefinition(request: CreateModelDefinitionRequest): ModelDefinition {
        val provider = request.provider.trim().lowercase()
        val model = request.model.trim()
        val displayName = request.displayName.trim().ifBlank { model }
        if (provider.length !in 1..64) throw badRequest("invalid_provider", "Provider is required")
        if (model.length !in 1..128) throw badRequest("invalid_model", "Model is required")
        if (request.contextWindow !in 1_024..2_000_000) {
            throw badRequest("invalid_context", "contextWindow is out of range")
        }
        if (request.inputPrice < 0.0 || request.outputPrice < 0.0) {
            throw badRequest("invalid_price", "Prices must be non-negative")
        }
        val capabilities = buildJsonArray {
            request.capabilities.forEach { add(JsonPrimitive(it)) }
        }.toString()
        return store.createDefinition(
            provider = provider,
            model = model,
            displayName = displayName,
            capabilitiesJson = capabilities,
            contextWindow = request.contextWindow,
            inputPrice = request.inputPrice,
            outputPrice = request.outputPrice,
        )
    }

    fun listDefinitions(): List<ModelDefinition> = store.listDefinitions()

    fun createBinding(request: CreateModelBindingRequest): ModelBinding {
        val profileId = validUuid(request.profileId, "invalid_profile")
        val definitionId = validUuid(request.modelDefinitionId, "invalid_model_definition")
        val credentialId = validUuid(request.credentialId, "invalid_credential")
        try {
            containers.get(profileId)
        } catch (error: ContainerException) {
            throw ModelException(error.error.code, error.error.message, error.error.status)
        }
        val definition = store.findDefinition(definitionId)
            ?: throw ModelException("model_not_found", "Model definition not found", 404)
        val credential = store.findCredential(credentialId)
            ?: throw ModelException("credential_not_found", "Credential not found", 404)
        if (credential.scope != CredentialScope.CLOUD_SERVER) {
            throw ModelException("credential_scope_mismatch", "Cloud bindings require a cloud credential", 409)
        }
        if (credential.status == CredentialStatus.INVALID || credential.status == CredentialStatus.REVOKED) {
            throw ModelException("credential_invalid", "Credential is not usable", 409)
        }
        if (definition.provider != credential.provider) {
            throw ModelException("provider_mismatch", "Model and credential providers do not match", 409)
        }

        val binding = store.createBinding(profileId, definitionId, credentialId)
        store.updateCredentialStatus(credentialId, CredentialStatus.ACTIVE)
        return binding
    }

    fun listBindings(profileId: String): List<ModelBinding> {
        val id = validUuid(profileId, "invalid_profile")
        return store.listBindings(id)
    }

    fun deleteBinding(bindingId: String) {
        val id = validUuid(bindingId, "invalid_binding")
        if (store.findBinding(id) == null) {
            throw ModelException("binding_not_found", "Model binding not found", 404)
        }
        store.deleteBinding(id)
    }

    fun resolve(bindingId: String, profileId: String): ResolvedModelBinding {
        val id = validUuid(bindingId, "binding_not_found")
        val binding = store.findBinding(id)
            ?: throw ModelException("binding_not_found", "Model binding not found", 404)
        if (binding.profileId != profileId) {
            throw ModelException("binding_not_for_profile", "Model binding belongs to another container", 409)
        }
        if (binding.status != BindingStatus.ACTIVE.name) {
            throw ModelException("binding_unavailable", "Model binding is not active", 409)
        }
        val definition = store.findDefinition(binding.modelDefinitionId)
            ?: throw ModelException("model_not_found", "Model definition not found", 404)
        val credential = store.findCredential(binding.credentialId)
            ?: throw ModelException("credential_not_found", "Credential not found", 404)
        if (credential.scope != CredentialScope.CLOUD_SERVER) {
            throw ModelException("credential_scope_mismatch", "Cloud runtime cannot use a local credential", 409)
        }
        if (credential.status != CredentialStatus.ACTIVE) {
            throw ModelException("credential_invalid", "Credential is not active", 409)
        }
        return ResolvedModelBinding(binding, definition, credential)
    }

    fun decryptCredential(credential: ProviderCredential): String {
        if (credential.keyVersion != 1) {
            throw ModelException("credential_version_unsupported", "Unsupported credential key version", 409)
        }
        return try {
            vault.decrypt(
                EncryptedSecret(
                    ciphertext = credential.encryptedSecret,
                    nonce = credential.secretNonce,
                    keyVersion = credential.keyVersion,
                ),
            )
        } catch (_: Exception) {
            store.updateCredentialStatus(credential.id, CredentialStatus.INVALID)
            throw ModelException("credential_invalid", "Credential could not be decrypted", 409)
        }
    }

    fun markCredentialInvalid(credentialId: String) {
        store.updateCredentialStatus(credentialId, CredentialStatus.INVALID)
    }

    fun recordCall(
        sessionId: String?,
        turnId: String?,
        bindingId: String?,
        provider: String,
        model: String,
        usage: ModelCallUsage?,
        status: String,
        errorCode: String? = null,
    ) {
        store.recordCall(
            sessionId = sessionId,
            turnId = turnId,
            bindingId = bindingId,
            provider = provider,
            model = model,
            inputTokens = usage?.inputTokens,
            outputTokens = usage?.outputTokens,
            latencyMs = usage?.latencyMs ?: 0,
            status = status,
            errorCode = errorCode,
        )
    }

    private fun ProviderCredential.toSummary(): CredentialSummary {
        return CredentialSummary(
            id = id,
            scope = scope.name,
            provider = provider,
            baseUrl = baseUrl,
            status = status.name,
            hasSecret = encryptedSecret.isNotEmpty(),
            keyVersion = keyVersion,
            createdAt = createdAt,
        )
    }

    private fun validUuid(value: String, code: String): String {
        return try {
            UUID.fromString(value.trim()).toString()
        } catch (_: Exception) {
            throw ModelException(code, "A valid id is required", 400)
        }
    }

    private fun badRequest(code: String, message: String): ModelException {
        return ModelException(code, message, 400)
    }
}
