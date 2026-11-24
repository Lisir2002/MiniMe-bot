package com.minibot.control.container

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.AuthConfig
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.RestartPolicy
import com.github.dockerjava.api.model.Volume
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient
import java.time.Duration

class DockerJavaEngine(
    dockerHost: String = "unix:///var/run/docker.sock",
    private val registryHost: String? = null,
    private val registryUsername: String? = null,
    private val registryToken: String? = null,
) : DockerEngine, AutoCloseable {
    private val dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(dockerHost)
        .build()

    private val httpClient: DockerHttpClient = ApacheDockerHttpClient.Builder()
        .dockerHost(dockerConfig.dockerHost)
        .sslConfig(dockerConfig.sslConfig)
        .maxConnections(100)
        .connectionTimeout(Duration.ofSeconds(30))
        .responseTimeout(Duration.ofSeconds(60))
        .build()

    private val client: DockerClient = DockerClientImpl.getInstance(dockerConfig, httpClient)

    override fun pullImage(imageRef: String) {
        try {
            val command = client.pullImageCmd(imageRef)
            registryAuthFor(imageRef)?.let { command.withAuthConfig(it) }
            command.start().awaitCompletion()
        } catch (error: Exception) {
            throw DockerEngineException("Failed to pull image $imageRef", error)
        }
    }

    override fun createContainer(spec: CreateContainerSpec): String {
        try {
            val binds = listOf(
                Bind(spec.workspaceHostPath, Volume("/workspace")),
                Bind(spec.minibotHomeHostPath, Volume("/home/minibot/.minibot")),
                Bind(spec.toolLogsHostPath, Volume("/home/minibot/.minibot/logs"), AccessMode.ro),
            )

            val hostConfig = HostConfig.newHostConfig()
                .withBinds(binds)
                .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
                .also { config ->
                    spec.resourcePolicy.memoryMb?.let { config.withMemory(it * 1024L * 1024L) }
                    spec.resourcePolicy.cpuCores?.let {
                        config.withNanoCPUs((it * 1_000_000_000.0).toLong())
                    }
                }

            return client.createContainerCmd(spec.imageRef)
                .withName(spec.name)
                .withLabels(spec.labels)
                .withEnv(spec.environment.map { (key, value) -> "$key=$value" })
                .withWorkingDir("/workspace")
                .withUser("minibot")
                .withHostConfig(hostConfig)
                .withTty(false)
                .withStdinOpen(false)
                .exec()
                .id
        } catch (error: Exception) {
            throw DockerEngineException("Failed to create container ${spec.name}", error)
        }
    }

    override fun startContainer(containerId: String) {
        try {
            client.startContainerCmd(containerId).exec()
        } catch (error: Exception) {
            throw DockerEngineException("Failed to start container $containerId", error)
        }
    }

    override fun stopContainer(containerId: String, timeoutSeconds: Int) {
        try {
            client.stopContainerCmd(containerId)
                .withTimeout(timeoutSeconds)
                .exec()
        } catch (error: Exception) {
            throw DockerEngineException("Failed to stop container $containerId", error)
        }
    }

    override fun removeContainer(containerId: String, removeVolumes: Boolean) {
        try {
            client.removeContainerCmd(containerId)
                .withRemoveVolumes(removeVolumes)
                .withForce(true)
                .exec()
        } catch (error: Exception) {
            throw DockerEngineException("Failed to remove container $containerId", error)
        }
    }

    override fun inspectContainer(containerId: String): DockerContainerState? {
        return try {
            val response = client.inspectContainerCmd(containerId).exec()
            DockerContainerState(
                id = response.id ?: containerId,
                running = response.state?.running == true,
                status = response.state?.status ?: "unknown",
                labels = response.config?.labels ?: emptyMap(),
            )
        } catch (error: Exception) {
            if (isNotFound(error)) null else throw DockerEngineException("Failed to inspect container $containerId", error)
        }
    }

    override fun listManagedContainers(labelKey: String, labelValue: String): List<DockerContainerState> {
        return try {
            client.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(mapOf(labelKey to labelValue))
                .exec()
                .map { container ->
                    DockerContainerState(
                        id = container.id,
                        running = container.state == "running",
                        status = container.state ?: "unknown",
                        labels = container.labels ?: emptyMap(),
                    )
                }
        } catch (error: Exception) {
            throw DockerEngineException("Failed to list managed containers", error)
        }
    }

    override fun exec(
        containerId: String,
        command: String,
        cwd: String?,
        timeoutMs: Long,
        onChunk: (stream: String, chunk: ByteArray) -> Unit,
    ): DockerExecResult {
        return try {
            client.execInContainer(containerId, command, cwd, timeoutMs, onChunk)
        } catch (error: Exception) {
            throw DockerEngineException("Failed to execute command in $containerId", error)
        }
    }

    override fun close() {
        client.close()
    }

    private fun registryAuthFor(imageRef: String): AuthConfig? {
        return buildRegistryAuth(imageRef, registryHost, registryUsername, registryToken)
    }

    private fun isNotFound(error: Exception): Boolean {
        return error.message?.contains("No such container", ignoreCase = true) == true ||
            error.message?.contains("not found", ignoreCase = true) == true
    }
}

internal fun buildRegistryAuth(
    imageRef: String,
    registryHost: String?,
    registryUsername: String?,
    registryToken: String?,
): AuthConfig? {
    val host = registryHost?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val username = registryUsername?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val token = registryToken?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedRef = imageRef.trim().lowercase()
    if (!normalizedRef.startsWith("$host/")) return null
    if (normalizedRef.substringAfter("$host/").startsWith("/")) return null
    return AuthConfig()
        .withUsername(username)
        .withPassword(token)
        .withRegistryAddress(host)
}
