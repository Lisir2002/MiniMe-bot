package com.minibot.control.api

import kotlinx.serialization.Serializable
import com.minibot.control.container.ResourcePolicy

@Serializable
data class HealthResponse(
    val status: String,
    val time: String,
)

@Serializable
data class DeploymentImageOption(
    val id: String,
    val displayName: String,
    val imageRef: String,
    val architecture: String,
    val distro: String,
)

@Serializable
data class DeploymentComponentOption(
    val id: String,
    val displayName: String,
    val category: String,
    val description: String,
)

@Serializable
data class ResourcePreset(
    val id: String,
    val displayName: String,
    val resourcePolicy: ResourcePolicy,
)

@Serializable
data class DeploymentOptionsResponse(
    val images: List<DeploymentImageOption>,
    val components: List<DeploymentComponentOption>,
    val packageManagers: List<String>,
    val presets: List<ResourcePreset>,
)

@Serializable
data class UpdateContainerRequest(
    val pinned: Boolean,
)

@Serializable
data class PairClaimRequest(
    val bootstrapToken: String,
    val devicePublicKey: String,
    val deviceName: String = "Android",
)

@Serializable
data class PairClaimResponse(
    val deviceId: String,
    val deviceToken: String,
    val serverId: String,
    val serverName: String,
)

@Serializable
data class ServerInfoResponse(
    val serverId: String,
    val serverName: String,
    val timezone: String,
    val paired: Boolean,
    val version: String,
    val time: String,
    val accessMode: String = "ip",
    val publicUrl: String? = null,
    val domain: String? = null,
    val architecture: String = "amd64",
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
)
