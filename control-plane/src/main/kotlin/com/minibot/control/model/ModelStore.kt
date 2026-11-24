package com.minibot.control.model

interface ModelStore {
    fun createCredential(
        scope: CredentialScope,
        provider: String,
        baseUrl: String,
        encryptedSecret: ByteArray,
        secretNonce: ByteArray,
        keyVersion: Int,
    ): ProviderCredential

    fun findCredential(credentialId: String): ProviderCredential?

    fun listCredentials(): List<ProviderCredential>

    fun updateCredentialStatus(credentialId: String, status: CredentialStatus): ProviderCredential?

    fun deleteCredential(credentialId: String)

    fun createDefinition(
        provider: String,
        model: String,
        displayName: String,
        capabilitiesJson: String,
        contextWindow: Int,
        inputPrice: Double,
        outputPrice: Double,
    ): ModelDefinition

    fun findDefinition(definitionId: String): ModelDefinition?

    fun listDefinitions(): List<ModelDefinition>

    fun createBinding(
        profileId: String,
        definitionId: String,
        credentialId: String,
    ): ModelBinding

    fun findBinding(bindingId: String): ModelBinding?

    fun listBindings(profileId: String): List<ModelBinding>

    fun updateBindingStatus(bindingId: String, status: BindingStatus): ModelBinding?

    fun deleteBinding(bindingId: String)

    fun recordCall(
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
    )
}
