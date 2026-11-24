package com.minibot.control.container

import kotlinx.serialization.Serializable

enum class ContainerKind {
    LOCAL,
    CLOUD,
}

enum class ContainerPhase {
    PROVISIONING,
    RUNNING,
    STOPPING,
    STOPPED,
    ARCHIVED,
    ERROR,
    DELETING,
}

@Serializable
data class ResourcePolicy(
    val cpuCores: Double? = null,
    val memoryMb: Long? = null,
    val diskMb: Long? = null,
)

@Serializable
data class PackageSpec(
    val ecosystem: String,
    val name: String,
    val version: String? = null,
)

@Serializable
data class ContainerProfile(
    val id: String,
    val kind: ContainerKind,
    val name: String,
    val imageRef: String,
    val imageDigest: String?,
    val resourcePolicy: ResourcePolicy,
    val components: List<String>,
    val packages: List<PackageSpec>,
    val createdAt: String,
)

@Serializable
data class ContainerInstance(
    val id: String,
    val profileId: String,
    val containerId: String?,
    val phase: ContainerPhase,
    val pinned: Boolean,
    val lastActivityAt: String?,
    val lastError: String?,
)

@Serializable
data class ContainerActivity(
    val activeTurns: Int = 0,
    val activeTerminals: Int = 0,
    val pendingApprovals: Int = 0,
    val pinned: Boolean = false,
)

@Serializable
data class ContainerSummary(
    val profile: ContainerProfile,
    val instance: ContainerInstance,
    val activity: ContainerActivity,
)

data class VolumeLayout(
    val root: String,
    val workspace: String,
    val minibotHome: String,
    val toolLogs: String,
)

data class CreateContainerSpec(
    val profileId: String,
    val name: String,
    val imageRef: String,
    val workspaceHostPath: String,
    val minibotHomeHostPath: String,
    val toolLogsHostPath: String,
    val labels: Map<String, String>,
    val environment: Map<String, String>,
    val resourcePolicy: ResourcePolicy,
)

data class DockerContainerState(
    val id: String,
    val running: Boolean,
    val status: String,
    val labels: Map<String, String>,
)

data class DockerExecResult(
    val exitCode: Int,
    val durationMs: Long,
    val timedOut: Boolean,
)

interface DockerEngine {
    fun pullImage(imageRef: String)

    fun createContainer(spec: CreateContainerSpec): String

    fun startContainer(containerId: String)

    fun stopContainer(containerId: String, timeoutSeconds: Int = 10)

    fun removeContainer(containerId: String, removeVolumes: Boolean = false)

    fun inspectContainer(containerId: String): DockerContainerState?

    fun listManagedContainers(labelKey: String, labelValue: String): List<DockerContainerState>

    fun exec(
        containerId: String,
        command: String,
        cwd: String?,
        timeoutMs: Long,
        onChunk: (stream: String, chunk: ByteArray) -> Unit,
    ): DockerExecResult
}
