package com.mini.bot.feature.settings.domain.model

import java.net.URI

/**
 * 云端控制面地址。支持两种访问方式：
 * - IP + HTTP 测试模式，默认端口 8787；
 * - 域名 + HTTPS，默认端口 443，证书由服务器反向代理提供。
 *
 * 域名是否真正可达由 /health 检测决定；这里只做格式和协议校验。
 */
data class CloudControlPlaneEndpoint(
    val baseUrl: String,
    val host: String,
    val port: Int,
    val secure: Boolean
) {
    companion object {
        const val DEFAULT_HTTP_PORT = 8787
        const val DEFAULT_HTTPS_PORT = 443

        fun normalize(raw: String): CloudEndpointParseResult {
            val input = raw.trim()
            if (input.isEmpty()) return CloudEndpointParseResult.Error(CloudEndpointError.EMPTY)

            return when {
                input.startsWith("http://", ignoreCase = true) -> parseHttpIp(input)
                input.startsWith("https://", ignoreCase = true) -> parseHttpsDomain(input)
                input.contains("://") -> CloudEndpointParseResult.Error(CloudEndpointError.INVALID_FORMAT)
                looksLikeDottedNumber(input) -> parseHttpIp("http://$input")
                else -> parseHttpsDomain("https://$input")
            }
        }

        private fun looksLikeDottedNumber(value: String): Boolean {
            val host = value.substringBefore(':')
            val parts = host.split(".")
            return parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit)
            }
        }

        private fun parseHttpIp(input: String): CloudEndpointParseResult {
            val uri = parseUri(input) ?: return invalidFormat()
            if (!uri.scheme.equals("http", ignoreCase = true)) return invalidFormat()
            if (hasUnsupportedParts(uri)) return invalidFormat()
            val host = uri.host ?: return invalidHostFromUri(uri)
            val octets = host.split(".")
            if (octets.size != 4 || octets.any { it.isEmpty() || !it.all(Char::isDigit) }) {
                // 用户把域名写成了 http://example.com；域名必须走 HTTPS。
                return CloudEndpointParseResult.Error(CloudEndpointError.HTTPS_REQUIRED)
            }
            val values = octets.mapNotNull { it.toIntOrNull() }
            if (values.size != 4 || values.any { it !in 0..255 }) {
                return CloudEndpointParseResult.Error(CloudEndpointError.INVALID_HOST)
            }
            val port = if (uri.port == -1) DEFAULT_HTTP_PORT else uri.port
            if (port !in 1..65535) return CloudEndpointParseResult.Error(CloudEndpointError.INVALID_PORT)
            return CloudEndpointParseResult.Success(
                CloudControlPlaneEndpoint(
                    baseUrl = "http://$host:$port",
                    host = host,
                    port = port,
                    secure = false
                )
            )
        }

        private fun parseHttpsDomain(input: String): CloudEndpointParseResult {
            val uri = parseUri(input) ?: return invalidFormat()
            if (!uri.scheme.equals("https", ignoreCase = true)) return invalidFormat()
            if (hasUnsupportedParts(uri)) return invalidFormat()
            val host = uri.host?.lowercase() ?: return CloudEndpointParseResult.Error(CloudEndpointError.DOMAIN_INVALID)
            if (looksLikeDottedNumber(host) || host.contains(':')) {
                return CloudEndpointParseResult.Error(CloudEndpointError.DOMAIN_REQUIRED)
            }
            if (!isValidDomain(host)) {
                return CloudEndpointParseResult.Error(CloudEndpointError.DOMAIN_INVALID)
            }
            val port = if (uri.port == -1) DEFAULT_HTTPS_PORT else uri.port
            if (port !in 1..65535) return CloudEndpointParseResult.Error(CloudEndpointError.INVALID_PORT)
            val authority = if (port == DEFAULT_HTTPS_PORT) host else "$host:$port"
            return CloudEndpointParseResult.Success(
                CloudControlPlaneEndpoint(
                    baseUrl = "https://$authority",
                    host = host,
                    port = port,
                    secure = true
                )
            )
        }

        private fun parseUri(value: String): URI? = try {
            URI(value)
        } catch (_: Exception) {
            null
        }

        private fun invalidHostFromUri(uri: URI): CloudEndpointParseResult {
            // URI 会把 300.1.1.1 这类越界 IPv4 判成无 host，这里把“看起来像 IP 但越界”的情况
            // 区分为 INVALID_HOST，避免给用户报笼统的格式错误。
            val rawHost = uri.rawAuthority
                ?.substringAfterLast("@")
                ?.substringBeforeLast(":")
                .orEmpty()
            return if (rawHost.contains(".") && rawHost.all { it.isDigit() || it == '.' }) {
                CloudEndpointParseResult.Error(CloudEndpointError.INVALID_HOST)
            } else {
                invalidFormat()
            }
        }

        private fun invalidFormat() =
            CloudEndpointParseResult.Error(CloudEndpointError.INVALID_FORMAT)

        private fun hasUnsupportedParts(uri: URI): Boolean =
            !uri.userInfo.isNullOrEmpty() ||
                uri.query != null ||
                uri.fragment != null ||
                uri.path.orEmpty().let { it.isNotEmpty() && it != "/" }

        private fun isValidDomain(host: String): Boolean {
            if (host.length > 253) return false
            if (host.any { it.code > 127 }) return false
            val labels = host.split(".")
            if (labels.size < 2) return false
            return labels.all { label ->
                label.length in 1..63 &&
                    label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }
        }
    }
}

sealed interface CloudEndpointParseResult {
    data class Success(val endpoint: CloudControlPlaneEndpoint) : CloudEndpointParseResult

    data class Error(val error: CloudEndpointError) : CloudEndpointParseResult
}

enum class CloudEndpointError {
    EMPTY,
    HTTPS_REQUIRED,
    DOMAIN_REQUIRED,
    DOMAIN_INVALID,
    INVALID_HOST,
    INVALID_PORT,
    INVALID_FORMAT
}
