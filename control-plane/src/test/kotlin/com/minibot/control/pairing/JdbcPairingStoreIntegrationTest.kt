package com.minibot.control.pairing

import com.minibot.control.config.AppConfig
import com.minibot.control.db.Database
import com.minibot.control.security.TokenHasher
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JdbcPairingStoreIntegrationTest {
    @Test
    fun `migration creates tables and pairing is single use`() {
        val dbUrl = System.getenv("MINIBOT_TEST_DB_URL")
        assumeTrue(dbUrl != null, "MINIBOT_TEST_DB_URL is not set")

        val config = AppConfig(
            host = "127.0.0.1",
            port = 8787,
            databaseUrl = dbUrl!!,
            databaseUser = System.getenv("MINIBOT_TEST_DB_USER") ?: "minibot",
            databasePassword = System.getenv("MINIBOT_TEST_DB_PASSWORD") ?: "minibot-dev",
            serverName = "Integration Test",
            timezone = "UTC",
            pairingTtlSeconds = 3600,
            bootstrapToken = "bootstrap",
            bootstrapTokenFile = Paths.get("/tmp/minibot-integration-bootstrap.token"),
            masterKeyPath = Paths.get("/tmp/minibot-integration-master.key"),
            dataDir = Paths.get("/tmp"),
            dockerHost = "unix:///var/run/docker.sock",
        )

        Database(config).use { database ->
            database.migrate()
            database.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE server_settings, server_device, pairing_tokens")
                }
            }

            val store = JdbcPairingStore(database.dataSource)
            val serverId = store.ensureServer(config.serverName, config.timezone)
            assertTrue(serverId.isNotBlank())

            val bootstrap = "integration-bootstrap"
            val tokenHash = TokenHasher.sha256Hex(bootstrap)
            store.seedPairingToken(tokenHash, Instant.now().plusSeconds(3600))

            val service = PairingService(store, serverId, config.serverName)
            val first = service.claim(bootstrap, "public-key", "Pixel")
            assertTrue(first is ClaimResult.Success)
            first as ClaimResult.Success
            assertNotNull(service.authenticate(first.deviceToken))

            val second = service.claim(bootstrap, "public-key-2", "Tablet")
            assertEquals("device_already_paired", (second as ClaimResult.Failure).code)
        }
    }
}
