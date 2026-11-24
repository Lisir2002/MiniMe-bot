package com.minibot.control.config

import java.nio.file.Path
import java.nio.file.Paths

data class AppConfig(
    val host: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val serverName: String,
    val timezone: String,
    val pairingTtlSeconds: Long,
    val bootstrapToken: String?,
    val bootstrapTokenFile: Path,
    val masterKeyPath: Path,
    val dataDir: Path,
    val dockerHost: String,
    val registryHost: String? = null,
    val registryUsername: String? = null,
    val registryToken: String? = null,
    val accessMode: String = "ip",
    val publicUrl: String? = null,
    val domain: String? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val dataDir = Paths.get(env["MINIBOT_DATA_DIR"] ?: "/var/lib/minibot")
            return AppConfig(
                host = env["MINIBOT_HOST"] ?: "127.0.0.1",
                port = (env["MINIBOT_PORT"] ?: "8787").toInt(),
                databaseUrl = env["MINIBOT_DB_URL"] ?: "jdbc:postgresql://127.0.0.1:5432/minibot",
                databaseUser = env["MINIBOT_DB_USER"] ?: "minibot",
                databasePassword = env["MINIBOT_DB_PASSWORD"] ?: "",
                serverName = env["MINIBOT_SERVER_NAME"] ?: "Mini-AIBOT Cloud",
                timezone = env["MINIBOT_TIMEZONE"] ?: "Asia/Shanghai",
                pairingTtlSeconds = (env["MINIBOT_PAIRING_TTL_SECONDS"] ?: "3600").toLong(),
                bootstrapToken = env["MINIBOT_BOOTSTRAP_TOKEN"]?.takeIf { it.isNotBlank() },
                bootstrapTokenFile = Paths.get(
                    env["MINIBOT_BOOTSTRAP_TOKEN_FILE"]
                        ?: dataDir.resolve("bootstrap.token").toString()
                ),
                masterKeyPath = Paths.get(
                    env["MINIBOT_MASTER_KEY_PATH"]
                        ?: dataDir.resolve("master.key").toString()
                ),
                dataDir = dataDir,
                dockerHost = env["MINIBOT_DOCKER_HOST"] ?: "unix:///var/run/docker.sock",
                registryHost = env["MINIBOT_REGISTRY_HOST"]?.trim()?.takeIf { it.isNotBlank() },
                registryUsername = env["MINIBOT_REGISTRY_USERNAME"]?.trim()?.takeIf { it.isNotBlank() },
                registryToken = env["MINIBOT_REGISTRY_TOKEN"]?.trim()?.takeIf { it.isNotBlank() },
                accessMode = env["MINIBOT_ACCESS_MODE"]?.trim()?.takeIf { it.isNotBlank() } ?: "ip",
                publicUrl = env["MINIBOT_PUBLIC_URL"]?.trim()?.takeIf { it.isNotBlank() },
                domain = env["MINIBOT_DOMAIN"]?.trim()?.takeIf { it.isNotBlank() },
            )
        }
    }
}
