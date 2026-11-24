package com.mini.bot.feature.settings.data.remote

import com.mini.bot.feature.settings.domain.model.CloudControlPlaneEndpoint
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CloudPairRequest(
    val bootstrapToken: String,
    val devicePublicKey: String,
    val deviceName: String,
)

@Serializable
data class CloudPairResponse(
    val deviceId: String,
    val deviceToken: String,
    val serverId: String,
    val serverName: String,
)

@Serializable
data class CloudServerInfo(
    val serverId: String,
    val serverName: String,
    val paired: Boolean,
    val accessMode: String = "ip",
    val publicUrl: String? = null,
    val domain: String? = null,
    val architecture: String = "amd64",
)

@Serializable
data class CloudDeploymentImage(
    val id: String,
    val displayName: String,
    val imageRef: String,
    val architecture: String,
    val distro: String,
)

@Serializable
data class CloudResourcePolicy(
    val cpuCores: Double? = null,
    val memoryMb: Long? = null,
    val diskMb: Long? = null,
)

@Serializable
data class CloudResourcePreset(
    val id: String,
    val displayName: String,
    val resourcePolicy: CloudResourcePolicy = CloudResourcePolicy(),
)

@Serializable
data class CloudDeploymentOptions(
    val images: List<CloudDeploymentImage>,
    val presets: List<CloudResourcePreset> = emptyList(),
)

@Serializable
data class CloudPackageSpec(
    val ecosystem: String,
    val name: String,
    val version: String? = null,
)

@Serializable
data class CloudDeployRequest(
    val name: String,
    val imageRef: String,
    val resourcePolicy: CloudResourcePolicy = CloudResourcePolicy(),
    val components: List<String> = emptyList(),
    val packages: List<CloudPackageSpec> = emptyList(),
    val pinned: Boolean = false,
)

@Serializable
data class CloudContainerProfile(
    val id: String,
    val name: String,
    val imageRef: String,
)

@Serializable
data class CloudContainerInstance(
    val id: String,
    val profileId: String,
    val containerId: String? = null,
    val phase: String,
)

@Serializable
data class CloudContainerSummary(
    val profile: CloudContainerProfile,
    val instance: CloudContainerInstance,
)

@Serializable
private data class CloudApiError(
    val code: String = "",
    val message: String = "",
)

class CloudControlPlaneException(
    val statusCode: Int,
    val code: String,
    override val message: String,
) : Exception(message)

/**
 * 云控制面的最小客户端：配对、读服务器信息、读部署选项、创建容器。
 *
 * 只有 /health 不需要鉴权；其余请求都带设备 Token，且不忽略 TLS 证书错误。
 */
class CloudControlPlaneClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val deployReadTimeoutMs: Int = 180_000,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun pair(
        endpoint: CloudControlPlaneEndpoint,
        bootstrapToken: String,
        devicePublicKey: String,
        deviceName: String,
    ): CloudPairResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            CloudPairRequest.serializer(),
            CloudPairRequest(
                bootstrapToken = bootstrapToken.trim(),
                devicePublicKey = devicePublicKey,
                deviceName = deviceName.trim(),
            ),
        )
        val response = request(
            endpoint = endpoint,
            method = "POST",
            path = "/api/v1/pair/claim",
            token = null,
            body = body,
            readTimeout = readTimeoutMs,
        )
        json.decodeFromString(CloudPairResponse.serializer(), response)
    }

    suspend fun serverInfo(
        endpoint: CloudControlPlaneEndpoint,
        deviceToken: String,
    ): CloudServerInfo = withContext(Dispatchers.IO) {
        val body = request(
            endpoint = endpoint,
            method = "GET",
            path = "/api/v1/server/info",
            token = deviceToken,
            body = null,
            readTimeout = readTimeoutMs,
        )
        json.decodeFromString(CloudServerInfo.serializer(), body)
    }

    suspend fun deploymentOptions(
        endpoint: CloudControlPlaneEndpoint,
        deviceToken: String,
    ): CloudDeploymentOptions = withContext(Dispatchers.IO) {
        val body = request(
            endpoint = endpoint,
            method = "GET",
            path = "/api/v1/deploy/options",
            token = deviceToken,
            body = null,
            readTimeout = readTimeoutMs,
        )
        json.decodeFromString(CloudDeploymentOptions.serializer(), body)
    }

    suspend fun deployContainer(
        endpoint: CloudControlPlaneEndpoint,
        deviceToken: String,
        requestBody: CloudDeployRequest,
    ): CloudContainerSummary = withContext(Dispatchers.IO) {
        val body = json.encodeToString(CloudDeployRequest.serializer(), requestBody)
        val response = request(
            endpoint = endpoint,
            method = "POST",
            path = "/api/v1/containers",
            token = deviceToken,
            body = body,
            readTimeout = deployReadTimeoutMs,
        )
        json.decodeFromString(CloudContainerSummary.serializer(), response)
    }

    private fun request(
        endpoint: CloudControlPlaneEndpoint,
        method: String,
        path: String,
        token: String?,
        body: String?,
        readTimeout: Int,
    ): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("${endpoint.baseUrl}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                setReadTimeout(readTimeout)
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mini-AIBOT-Android")
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val error = runCatching {
                    json.decodeFromString(CloudApiError.serializer(), responseBody)
                }.getOrNull()
                throw CloudControlPlaneException(
                    statusCode = status,
                    code = error?.code.orEmpty().ifBlank { "http_$status" },
                    message = error?.message.orEmpty().ifBlank { "HTTP $status" },
                )
            }
            return responseBody
        } catch (e: CloudControlPlaneException) {
            throw e
        } catch (e: Exception) {
            throw CloudControlPlaneException(
                statusCode = -1,
                code = "network_error",
                message = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            connection?.disconnect()
        }
    }
}
