package com.minibot.control.api

import com.minibot.control.config.AppConfig
import com.minibot.control.container.ContainerService
import com.minibot.control.container.ContainerSummary
import com.minibot.control.container.DeployContainerRequest
import com.minibot.control.container.DeploymentCatalog
import com.minibot.control.session.CreateSessionRequest
import com.minibot.control.session.CreateTurnRequest
import com.minibot.control.session.EventHub
import com.minibot.control.session.SessionOperations
import com.minibot.control.session.SessionRecord
import com.minibot.control.session.SessionSnapshot
import com.minibot.control.session.StoredEvent
import com.minibot.control.session.TurnCreation
import com.minibot.control.session.TurnRecord
import com.minibot.control.pairing.ClaimResult
import com.minibot.control.pairing.FakePairingStore
import com.minibot.control.pairing.PairingService
import com.minibot.control.pairing.fixedClock
import com.minibot.control.security.TokenHasher
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiServerTest {
    private val bootstrapToken = "bootstrap-token-for-api-test"
    private val tokenHash = TokenHasher.sha256Hex(bootstrapToken)
    private val json = Json { ignoreUnknownKeys = true }

    private fun testConfig() = AppConfig(
        host = "127.0.0.1",
        port = 8787,
        databaseUrl = "jdbc:postgresql://127.0.0.1:5432/minibot",
        databaseUser = "minibot",
        databasePassword = "",
        serverName = "Mini-AIBOT Cloud",
        timezone = "Asia/Shanghai",
        pairingTtlSeconds = 3600,
        bootstrapToken = bootstrapToken,
        bootstrapTokenFile = Paths.get("/tmp/minibot-api-test-bootstrap.token"),
        masterKeyPath = Paths.get("/tmp/minibot-api-test-master.key"),
        dataDir = Paths.get("/tmp"),
        dockerHost = "unix:///var/run/docker.sock",
    )

    private fun ApplicationTestBuilder.setup() {
        val store = FakePairingStore(expectedTokenHash = tokenHash)
        val service = PairingService(store, "server-1", "Mini-AIBOT Cloud", fixedClock())
        application {
            apiModule(
                config = testConfig(),
                store = store,
                pairingService = service,
                containerManager = NoopContainerService,
                deploymentCatalog = DeploymentCatalog(),
                sessionService = NoopSessionOperations,
                eventHub = EventHub(),
                serverId = "server-1",
                serverName = "Mini-AIBOT Cloud",
                version = "0.2.0",
            )
        }
    }

    @Test
    fun `health is public`() = testApplication {
        setup()
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun `pair claim returns a device token usable for server info`() = testApplication {
        setup()
        val claim = client.post("/api/v1/pair/claim") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"bootstrapToken":"$bootstrapToken","devicePublicKey":"test-key","deviceName":"Pixel"}"""
            )
        }
        assertEquals(HttpStatusCode.OK, claim.status)
        val body = json.decodeFromString<PairClaimResponse>(claim.bodyAsText())
        assertTrue(body.deviceToken.isNotBlank())

        val info = client.get("/api/v1/server/info") {
            header(HttpHeaders.Authorization, "Bearer ${body.deviceToken}")
        }
        assertEquals(HttpStatusCode.OK, info.status)
        val infoBody = json.decodeFromString<ServerInfoResponse>(info.bodyAsText())
        assertEquals("server-1", infoBody.serverId)
        assertTrue(infoBody.paired)
        assertEquals("ip", infoBody.accessMode)
        assertNull(infoBody.publicUrl)
        assertEquals("amd64", infoBody.architecture)
    }

    @Test
    fun `server info rejects missing token`() = testApplication {
        setup()
        val response = client.get("/api/v1/server/info")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `containers reject missing token`() = testApplication {
        setup()
        val response = client.get("/api/v1/containers")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `sessions reject missing token`() = testApplication {
        setup()
        val response = client.get("/api/v1/sessions")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `sessions list is available after pairing`() = testApplication {
        setup()
        val claim = client.post("/api/v1/pair/claim") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"bootstrapToken":"$bootstrapToken","devicePublicKey":"test-key","deviceName":"Pixel"}"""
            )
        }
        val body = json.decodeFromString<PairClaimResponse>(claim.bodyAsText())
        val response = client.get("/api/v1/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${body.deviceToken}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `deployment options are available after pairing`() = testApplication {
        setup()
        val claim = client.post("/api/v1/pair/claim") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"bootstrapToken":"$bootstrapToken","devicePublicKey":"test-key","deviceName":"Pixel"}"""
            )
        }
        val body = json.decodeFromString<PairClaimResponse>(claim.bodyAsText())

        val options = client.get("/api/v1/deploy/options") {
            header(HttpHeaders.Authorization, "Bearer ${body.deviceToken}")
        }
        assertEquals(HttpStatusCode.OK, options.status)
        assertTrue(options.bodyAsText().contains("\"images\""))
        assertTrue(options.bodyAsText().contains("\"presets\""))
    }

    @Test
    fun `second claim is rejected`() = testApplication {
        setup()
        val first = client.post("/api/v1/pair/claim") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"bootstrapToken":"$bootstrapToken","devicePublicKey":"key-1","deviceName":"Pixel"}"""
            )
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/api/v1/pair/claim") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"bootstrapToken":"$bootstrapToken","devicePublicKey":"key-2","deviceName":"Tablet"}"""
            )
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertTrue(second.bodyAsText().contains("device_already_paired"))
    }
}


private object NoopSessionOperations : SessionOperations {
    override fun createSession(request: CreateSessionRequest): SessionRecord = unsupported()
    override fun listSessions(): List<SessionRecord> = emptyList()
    override fun snapshot(sessionId: String): SessionSnapshot = unsupported()
    override fun createTurn(sessionId: String, request: CreateTurnRequest): TurnCreation = unsupported()
    override fun cancelTurn(sessionId: String, turnId: String): TurnRecord = unsupported()
    override fun events(sessionId: String, afterSeq: Long, limit: Int): List<StoredEvent> = emptyList()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
}

private object NoopContainerService : ContainerService {
    override fun deploy(request: DeployContainerRequest): ContainerSummary = unsupported()
    override fun list(): List<ContainerSummary> = unsupported()
    override fun get(profileId: String): ContainerSummary = unsupported()
    override fun ensureRunning(profileId: String): ContainerSummary = unsupported()
    override fun stop(profileId: String): ContainerSummary = unsupported()
    override fun deleteInstance(profileId: String): ContainerSummary = unsupported()
    override fun archive(profileId: String, confirm: Boolean): ContainerSummary = unsupported()
    override fun setPinned(profileId: String, pinned: Boolean): ContainerSummary = unsupported()
    override fun reconcile() = Unit

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
}
