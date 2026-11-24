package com.minibot.control.container

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable

data class ContainerError(
    val code: String,
    val message: String,
    val status: Int,
)

class ContainerException(val error: ContainerError) : RuntimeException(error.message)

interface ContainerService {
    fun deploy(request: DeployContainerRequest): ContainerSummary
    fun list(): List<ContainerSummary>
    fun get(profileId: String): ContainerSummary
    fun ensureRunning(profileId: String): ContainerSummary
    fun stop(profileId: String): ContainerSummary
    fun deleteInstance(profileId: String): ContainerSummary
    fun archive(profileId: String, confirm: Boolean): ContainerSummary
    fun setPinned(profileId: String, pinned: Boolean): ContainerSummary
    fun reconcile()
}

class ContainerManager(
    private val engine: DockerEngine,
    private val store: ContainerStore,
    private val volumes: VolumeManager,
    private val serverId: String,
) : ContainerService {
    private val profileLocks = ConcurrentHashMap<String, ReentrantLock>()

    override fun deploy(request: DeployContainerRequest): ContainerSummary {
        validateDeployRequest(request)
        val profileId = UUID.randomUUID().toString()
        val layout = volumes.ensure(profileId)
        val profile = ContainerProfile(
            id = profileId,
            kind = ContainerKind.CLOUD,
            name = request.name.trim(),
            imageRef = request.imageRef.trim(),
            imageDigest = request.imageDigest?.trim()?.takeIf { it.isNotBlank() },
            resourcePolicy = request.resourcePolicy,
            components = request.components,
            packages = request.packages,
            createdAt = Instant.now().toString(),
        )
        store.createProfile(profile)

        val instance = ContainerInstance(
            id = UUID.randomUUID().toString(),
            profileId = profileId,
            containerId = null,
            phase = ContainerPhase.PROVISIONING,
            pinned = request.pinned,
            lastActivityAt = Instant.now().toString(),
            lastError = null,
        )
        store.saveInstance(instance)

        try {
            engine.pullImage(profile.imageRef)
            val containerId = engine.createContainer(
                CreateContainerSpec(
                    profileId = profileId,
                    name = containerName(profileId),
                    imageRef = profile.imageRef,
                    workspaceHostPath = layout.workspace,
                    minibotHomeHostPath = layout.minibotHome,
                    toolLogsHostPath = layout.toolLogs,
                    labels = labels(profileId),
                    environment = environment(profileId),
                    resourcePolicy = profile.resourcePolicy,
                ),
            )
            store.updateInstance(profileId, ContainerPhase.PROVISIONING, containerId = containerId)
            engine.startContainer(containerId)
            store.updateInstance(
                profileId = profileId,
                phase = ContainerPhase.RUNNING,
                containerId = containerId,
            )
            return summary(profile, store.findInstance(profileId))
        } catch (error: Exception) {
            store.updateInstance(
                profileId = profileId,
                phase = ContainerPhase.ERROR,
                lastError = error.message ?: error::class.simpleName,
            )
            throw ContainerException(
                ContainerError(
                    code = "container_deploy_failed",
                    message = "Failed to deploy container: ${error.message ?: error::class.simpleName}",
                    status = 500,
                ),
            )
        }
    }

    override fun list(): List<ContainerSummary> {
        return store.listProfiles().map { profile ->
            summary(profile, store.findInstance(profile.id))
        }
    }

    override fun get(profileId: String): ContainerSummary {
        val profile = store.findProfile(profileId) ?: throw notFound()
        return summary(profile, store.findInstance(profileId))
    }

    override fun ensureRunning(profileId: String): ContainerSummary {
        return profileLock(profileId).withLock {
            val profile = store.findProfile(profileId) ?: throw notFound()
            val instance = store.findInstance(profileId)
            if (instance?.phase == ContainerPhase.ARCHIVED) {
                throw ContainerException(
                    ContainerError("container_archived", "Container is archived", 409),
                )
            }

            val existingContainerId = instance?.containerId
            if (existingContainerId != null) {
                val state = engine.inspectContainer(existingContainerId)
                if (state != null) {
                    if (!state.running) {
                        engine.startContainer(existingContainerId)
                    }
                    store.updateInstance(profileId, ContainerPhase.RUNNING, containerId = existingContainerId)
                    return@withLock summary(profile, store.findInstance(profileId))
                }
            }

            val layout = volumes.ensure(profileId)
            val containerId = engine.createContainer(
                CreateContainerSpec(
                    profileId = profileId,
                    name = containerName(profileId),
                    imageRef = profile.imageRef,
                    workspaceHostPath = layout.workspace,
                    minibotHomeHostPath = layout.minibotHome,
                    toolLogsHostPath = layout.toolLogs,
                    labels = labels(profileId),
                    environment = environment(profileId),
                    resourcePolicy = profile.resourcePolicy,
                ),
            )
            store.saveInstance(
                ContainerInstance(
                    id = instance?.id ?: UUID.randomUUID().toString(),
                    profileId = profileId,
                    containerId = containerId,
                    phase = ContainerPhase.PROVISIONING,
                    pinned = instance?.pinned ?: false,
                    lastActivityAt = Instant.now().toString(),
                    lastError = null,
                ),
            )
            engine.startContainer(containerId)
            store.updateInstance(profileId, ContainerPhase.RUNNING, containerId = containerId)
            return@withLock summary(profile, store.findInstance(profileId))
        }
    }

    override fun stop(profileId: String): ContainerSummary {
        return profileLock(profileId).withLock {
            val profile = store.findProfile(profileId) ?: throw notFound()
            val instance = store.findInstance(profileId)
            val containerId = instance?.containerId
            if (containerId != null) {
                store.updateInstance(profileId, ContainerPhase.STOPPING, containerId = containerId)
                try {
                    engine.stopContainer(containerId)
                } catch (error: DockerEngineException) {
                    store.updateInstance(
                        profileId,
                        ContainerPhase.ERROR,
                        containerId = containerId,
                        lastError = error.message,
                    )
                    throw ContainerException(
                        ContainerError("container_stop_failed", error.message ?: "stop failed", 500),
                    )
                }
            }
            store.updateInstance(profileId, ContainerPhase.STOPPED, containerId = containerId)
            summary(profile, store.findInstance(profileId))
        }
    }

    /**
     * Delete the running instance without removing the container or volumes.
     * This matches the "delete instance = stop, archive = destroy" rule.
     */
    override fun deleteInstance(profileId: String): ContainerSummary {
        return stop(profileId)
    }

    override fun archive(profileId: String, confirm: Boolean): ContainerSummary {
        if (!confirm) {
            throw ContainerException(
                ContainerError("confirmation_required", "Archive requires confirm=true", 400),
            )
        }
        return profileLock(profileId).withLock {
            val profile = store.findProfile(profileId) ?: throw notFound()
            val instance = store.findInstance(profileId)
            instance?.containerId?.let { containerId ->
                runCatching { engine.stopContainer(containerId) }
                runCatching { engine.removeContainer(containerId, removeVolumes = false) }
            }
            store.updateInstance(profileId, ContainerPhase.ARCHIVED, lastError = null)
            store.deleteProfile(profileId)
            volumes.delete(profileId)
            summary(profile, instance?.copy(phase = ContainerPhase.ARCHIVED))
        }
    }

    override fun setPinned(profileId: String, pinned: Boolean): ContainerSummary {
        val profile = store.findProfile(profileId) ?: throw notFound()
        store.setPinned(profileId, pinned)
        return summary(profile, store.findInstance(profileId))
    }

    override fun reconcile() {
        store.listProfiles().forEach { profile ->
            val instance = store.findInstance(profile.id) ?: return@forEach
            val containerId = instance.containerId ?: return@forEach
            val state = engine.inspectContainer(containerId)
            when {
                state == null -> store.updateInstance(profile.id, ContainerPhase.STOPPED, containerId = containerId)
                state.running -> store.updateInstance(profile.id, ContainerPhase.RUNNING, containerId = containerId)
                else -> store.updateInstance(profile.id, ContainerPhase.STOPPED, containerId = containerId)
            }
        }
    }

    private fun summary(profile: ContainerProfile, instance: ContainerInstance?): ContainerSummary {
        return ContainerSummary(
            profile = profile,
            instance = instance ?: ContainerInstance(
                id = "",
                profileId = profile.id,
                containerId = null,
                phase = ContainerPhase.ERROR,
                pinned = false,
                lastActivityAt = null,
                lastError = "missing instance record",
            ),
            activity = ContainerActivity(pinned = instance?.pinned == true),
        )
    }

    private fun profileLock(profileId: String): ReentrantLock {
        return profileLocks.computeIfAbsent(profileId) { ReentrantLock() }
    }

    private fun labels(profileId: String): Map<String, String> = mapOf(
        "minibot.managed" to "true",
        "minibot.kind" to "CLOUD",
        "minibot.profile" to profileId,
        "minibot.server" to serverId,
    )

    private fun environment(profileId: String): Map<String, String> = mapOf(
        "HOME" to "/home/minibot",
        "MINIBOT_PROFILE_ID" to profileId,
        "MINIBOT_SERVER_ID" to serverId,
    )

    private fun containerName(profileId: String): String {
        return "minibot-${profileId.replace("-", "").take(20)}"
    }

    private fun notFound(): ContainerException = ContainerException(
        ContainerError("container_not_found", "Container profile not found", 404),
    )

    private fun validateDeployRequest(request: DeployContainerRequest) {
        val name = request.name.trim()
        if (name.length !in 1..64) {
            throw ContainerException(
                ContainerError("invalid_name", "Container name must be 1-64 characters", 400),
            )
        }
        val image = request.imageRef.trim()
        if (image.isBlank() || image.length > 255 || image.any { it.isWhitespace() }) {
            throw ContainerException(
                ContainerError("invalid_image", "A valid image reference is required", 400),
            )
        }
        if (request.resourcePolicy.cpuCores != null && request.resourcePolicy.cpuCores <= 0.0) {
            throw ContainerException(
                ContainerError("invalid_resource", "cpuCores must be positive", 400),
            )
        }
        if (request.resourcePolicy.memoryMb != null && request.resourcePolicy.memoryMb <= 0) {
            throw ContainerException(
                ContainerError("invalid_resource", "memoryMb must be positive", 400),
            )
        }
        request.packages.forEach { packageSpec ->
            if (packageSpec.ecosystem !in ALLOWED_ECOSYSTEMS) {
                throw ContainerException(
                    ContainerError("unsupported_ecosystem", "Unsupported package ecosystem: ${packageSpec.ecosystem}", 400),
                )
            }
            if (!PACKAGE_NAME.matches(packageSpec.name)) {
                throw ContainerException(
                    ContainerError("invalid_package", "Invalid package name: ${packageSpec.name}", 400),
                )
            }
            val version = packageSpec.version
            if (version != null && !PACKAGE_VERSION.matches(version)) {
                throw ContainerException(
                    ContainerError("invalid_package", "Invalid package version: $version", 400),
                )
            }
        }
    }

    private companion object {
        val ALLOWED_ECOSYSTEMS = setOf(
            "apt", "apk", "npm", "pnpm", "yarn", "pip", "uv",
            "cargo", "gem", "composer", "go",
        )
        val PACKAGE_NAME = Regex("^[A-Za-z0-9@._+~\\-\\[\\]]{1,128}$")
        val PACKAGE_VERSION = Regex("^[A-Za-z0-9._+~^<>=|*!\\-]{1,64}$")
    }
}

@Serializable
data class DeployContainerRequest(
    val name: String,
    val imageRef: String,
    val imageDigest: String? = null,
    val resourcePolicy: ResourcePolicy = ResourcePolicy(),
    val components: List<String> = emptyList(),
    val packages: List<PackageSpec> = emptyList(),
    val pinned: Boolean = false,
)
