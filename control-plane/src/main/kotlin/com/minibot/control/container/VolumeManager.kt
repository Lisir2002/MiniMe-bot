package com.minibot.control.container

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

class VolumeManager(private val dataDir: Path) {
    private val volumesRoot: Path = dataDir.resolve("volumes").toAbsolutePath().normalize()

    fun ensure(profileId: String): VolumeLayout {
        val profileRoot = profileRoot(profileId)
        val workspace = profileRoot.resolve("workspace")
        val minibotHome = profileRoot.resolve("home/.minibot")
        val toolLogs = profileRoot.resolve("tool-logs")

        listOf(
            workspace,
            minibotHome.resolve("skills"),
            minibotHome.resolve("plugins"),
            toolLogs,
        ).forEach { directory ->
            Files.createDirectories(directory)
            setDirectoryPermissions(directory)
        }

        return VolumeLayout(
            root = profileRoot.toString(),
            workspace = workspace.toString(),
            minibotHome = minibotHome.toString(),
            toolLogs = toolLogs.toString(),
        )
    }

    fun delete(profileId: String) {
        val root = profileRoot(profileId)
        if (!root.startsWith(volumesRoot)) {
            throw IllegalArgumentException("Refusing to delete path outside volumes root: $root")
        }
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                Files.deleteIfExists(path)
            }
        }
    }

    private fun profileRoot(profileId: String): Path {
        val uuid = runCatching { UUID.fromString(profileId) }.getOrElse {
            throw IllegalArgumentException("Invalid profile id: $profileId")
        }
        return volumesRoot.resolve(uuid.toString()).normalize()
    }

    private fun setDirectoryPermissions(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem.
        }
    }
}
