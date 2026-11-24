package com.minibot.control.model

import kotlinx.serialization.Serializable

enum class CredentialScope {
    LOCAL_APP,
    CLOUD_SERVER,
}

enum class CredentialStatus {
    UNASSIGNED,
    ACTIVE,
    INVALID,
    REVOKED,
}

enum class BindingStatus {
    ACTIVE,
    INACTIVE,
    ERROR,
}

@Serializable
data class CredentialSummary(
    val id: String,
    val scope: String,
    val provider: String,
    val baseUrl: String,
    val status: String,
    val hasSecret: Boolean,
    val keyVersion: Int,
    val createdAt: String,
)

data class ProviderCredential(
    val id: String,
    val scope: CredentialScope,
    val provider: String,
    val baseUrl: String,
    val encryptedSecret: ByteArray,
    val secretNonce: ByteArray,
    val keyVersion: Int,
    val status: CredentialStatus,
    val createdAt: String,
    val revokedAt: String?,
)

@Serializable
data class ModelDefinition(
    val id: String,
    val provider: String,
    val model: String,
    val displayName: String,
    val capabilitiesJson: String,
    val contextWindow: Int,
    val inputPrice: Double,
    val outputPrice: Double,
    val createdAt: String,
)

@Serializable
data class ModelBinding(
    val id: String,
    val profileId: String,
    val modelDefinitionId: String,
    val credentialId: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

data class ResolvedModelBinding(
    val binding: ModelBinding,
    val definition: ModelDefinition,
    val credential: ProviderCredential,
)

@Serializable
data class CreateCredentialRequest(
    val provider: String,
    val baseUrl: String,
    val secret: String,
    val displayName: String? = null,
)

@Serializable
data class CreateModelDefinitionRequest(
    val provider: String,
    val model: String,
    val displayName: String,
    val capabilities: List<String> = emptyList(),
    val contextWindow: Int = 32_768,
    val inputPrice: Double = 0.0,
    val outputPrice: Double = 0.0,
)

@Serializable
data class CreateModelBindingRequest(
    val profileId: String,
    val modelDefinitionId: String,
    val credentialId: String,
)

data class ModelGatewayMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val name: String? = null,
    val toolCalls: List<ModelToolCall> = emptyList(),
)

data class ModelToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String,
)

data class ModelToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ModelCallRequest(
    val model: String,
    val messages: List<ModelGatewayMessage>,
    val maxTokens: Int = 4096,
    val tools: List<ModelToolDefinition> = emptyList(),
)

data class ModelCallUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long,
)

data class ModelCallResult(
    val text: String,
    val usage: ModelCallUsage,
    val finishReason: String?,
    val toolCalls: List<ModelToolCall> = emptyList(),
)

class ModelException(
    val code: String,
    message: String,
    val status: Int = 500,
) : RuntimeException(message)
