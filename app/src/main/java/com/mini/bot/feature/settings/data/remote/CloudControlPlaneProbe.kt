package com.mini.bot.feature.settings.data.remote

import com.mini.bot.feature.settings.domain.model.CloudControlPlaneEndpoint
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CloudControlPlaneProbeResult(
    val reachable: Boolean,
    val statusCode: Int,
    val detail: String
)

/**
 * 只用未鉴权的 /health 判断网络是否通，不读取会话、不发送设备 Token。
 */
class CloudControlPlaneProbe(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000
) {
    suspend fun check(endpoint: CloudControlPlaneEndpoint): CloudControlPlaneProbeResult =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("${endpoint.baseUrl}/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                    .take(512)
                CloudControlPlaneProbeResult(
                    reachable = code in 200..299 && body.contains("\"status\":\"ok\""),
                    statusCode = code,
                    detail = body.ifBlank { "HTTP $code" }
                )
            } catch (e: Exception) {
                CloudControlPlaneProbeResult(
                    reachable = false,
                    statusCode = -1,
                    detail = e.message ?: e.javaClass.simpleName
                )
            } finally {
                connection?.disconnect()
            }
        }
}
