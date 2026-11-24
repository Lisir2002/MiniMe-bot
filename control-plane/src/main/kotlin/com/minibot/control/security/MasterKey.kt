package com.minibot.control.security

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

object MasterKey {
    private const val KEY_BYTES = 32

    fun loadOrCreate(path: Path): ByteArray {
        if (Files.exists(path)) {
            val key = Files.readAllBytes(path)
            require(key.size == KEY_BYTES) {
                "Master key $path must contain exactly $KEY_BYTES bytes"
            }
            return key
        }

        val parent = path.toAbsolutePath().parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        val key = ByteArray(KEY_BYTES)
        SecureRandom().nextBytes(key)
        Files.write(path, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        restrictPermissions(path)
        return key
    }

    private fun restrictPermissions(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem. The installer still creates the file under /var/lib/minibot.
        }
    }
}
