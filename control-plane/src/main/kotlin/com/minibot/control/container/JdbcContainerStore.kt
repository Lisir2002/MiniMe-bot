package com.minibot.control.container

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcContainerStore(private val dataSource: DataSource) : ContainerStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun createProfile(profile: ContainerProfile): ContainerProfile {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO container_profiles (
                    id, kind, name, image_ref, image_digest,
                    resource_policy_json, components_json, packages_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, now(), now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(profile.id))
                statement.setString(2, profile.kind.name)
                statement.setString(3, profile.name)
                statement.setString(4, profile.imageRef)
                statement.setString(5, profile.imageDigest)
                statement.setString(6, json.encodeToString(profile.resourcePolicy))
                statement.setString(7, json.encodeToString(profile.components))
                statement.setString(8, json.encodeToString(profile.packages))
                statement.executeUpdate()
            }
        }
        return profile
    }

    override fun findProfile(profileId: String): ContainerProfile? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, kind, name, image_ref, image_digest,
                       resource_policy_json, components_json, packages_json, created_at
                FROM container_profiles
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(profileId))
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toProfile() else null
                }
            }
        }
    }

    override fun listProfiles(): List<ContainerProfile> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, kind, name, image_ref, image_digest,
                       resource_policy_json, components_json, packages_json, created_at
                FROM container_profiles
                ORDER BY created_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val profiles = mutableListOf<ContainerProfile>()
                    while (result.next()) profiles += result.toProfile()
                    return profiles
                }
            }
        }
    }

    override fun findInstance(profileId: String): ContainerInstance? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, profile_id, container_id, phase, pinned, last_activity_at, last_error
                FROM container_instances
                WHERE profile_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(profileId))
                statement.executeQuery().use { result ->
                    return if (result.next()) result.toInstance() else null
                }
            }
        }
    }

    override fun saveInstance(instance: ContainerInstance) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO container_instances (
                    id, profile_id, container_id, phase, pinned, last_activity_at, last_error
                ) VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?)
                ON CONFLICT (profile_id) DO UPDATE SET
                    container_id = EXCLUDED.container_id,
                    phase = EXCLUDED.phase,
                    pinned = EXCLUDED.pinned,
                    last_activity_at = EXCLUDED.last_activity_at,
                    last_error = EXCLUDED.last_error,
                    updated_at = now()
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.fromString(instance.id))
                statement.setObject(2, UUID.fromString(instance.profileId))
                statement.setString(3, instance.containerId)
                statement.setString(4, instance.phase.name)
                statement.setBoolean(5, instance.pinned)
                statement.setString(6, instance.lastActivityAt)
                statement.setString(7, instance.lastError)
                statement.executeUpdate()
            }
        }
    }

    override fun updateInstance(
        profileId: String,
        phase: ContainerPhase,
        containerId: String?,
        lastError: String?,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE container_instances
                SET phase = ?,
                    container_id = COALESCE(?, container_id),
                    last_error = ?,
                    last_activity_at = now(),
                    updated_at = now()
                WHERE profile_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, phase.name)
                statement.setString(2, containerId)
                statement.setString(3, lastError)
                statement.setObject(4, UUID.fromString(profileId))
                statement.executeUpdate()
            }
        }
    }

    override fun setPinned(profileId: String, pinned: Boolean) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE container_instances SET pinned = ?, updated_at = now() WHERE profile_id = ?",
            ).use { statement ->
                statement.setBoolean(1, pinned)
                statement.setObject(2, UUID.fromString(profileId))
                statement.executeUpdate()
            }
        }
    }

    override fun deleteProfile(profileId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM container_profiles WHERE id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(profileId))
                statement.executeUpdate()
            }
        }
    }

    private fun ResultSet.toProfile(): ContainerProfile {
        return ContainerProfile(
            id = getObject("id").toString(),
            kind = ContainerKind.valueOf(getString("kind")),
            name = getString("name"),
            imageRef = getString("image_ref"),
            imageDigest = getString("image_digest"),
            resourcePolicy = runCatching {
                json.decodeFromString(ResourcePolicy.serializer(), getString("resource_policy_json"))
            }.getOrDefault(ResourcePolicy()),
            components = runCatching {
                json.decodeFromString<List<String>>(getString("components_json"))
            }.getOrDefault(emptyList()),
            packages = runCatching {
                json.decodeFromString<List<PackageSpec>>(getString("packages_json"))
            }.getOrDefault(emptyList()),
            createdAt = getTimestamp("created_at")?.toInstant()?.toString() ?: Instant.now().toString(),
        )
    }

    private fun ResultSet.toInstance(): ContainerInstance {
        return ContainerInstance(
            id = getObject("id").toString(),
            profileId = getObject("profile_id").toString(),
            containerId = getString("container_id"),
            phase = ContainerPhase.valueOf(getString("phase")),
            pinned = getBoolean("pinned"),
            lastActivityAt = getTimestamp("last_activity_at")?.toInstant()?.toString(),
            lastError = getString("last_error"),
        )
    }
}
