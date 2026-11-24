package com.minibot.control.pairing

import com.minibot.control.config.AppConfig
import com.minibot.control.security.TokenHasher
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant

object BootstrapToken {
    private val logger = LoggerFactory.getLogger(BootstrapToken::class.java)

    fun prepare(
        config: AppConfig,
        store: PairingStore,
        clock: Clock = Clock.systemUTC(),
    ): String? {
        if (store.activeDevice() != null) {
            clear(config.bootstrapTokenFile)
            return null
        }

        val token = config.bootstrapToken?.trim()?.takeIf { it.isNotBlank() }
            ?: readFile(config.bootstrapTokenFile)
            ?: TokenHasher.randomToken(24).also { generated ->
                writeFile(config.bootstrapTokenFile, generated)
                logger.info(
                    "Generated bootstrap token at {}. Pair the first device before this file is removed.",
                    config.bootstrapTokenFile,
                )
            }

        store.seedPairingToken(
            tokenHash = TokenHasher.sha256Hex(token),
            expiresAt = Instant.now(clock).plusSeconds(config.pairingTtlSeconds),
        )
        return token
    }

    fun clear(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (error: Exception) {
            logger.warn("Failed to remove bootstrap token file {}", path, error)
        }
    }

    private fun readFile(path: Path): String? {
        if (!Files.exists(path)) return null
        return runCatching { Files.readString(path).trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun writeFile(path: Path, token: String) {
        val parent = path.toAbsolutePath().parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.writeString(
            path,
            token,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem.
        }
    }
}
