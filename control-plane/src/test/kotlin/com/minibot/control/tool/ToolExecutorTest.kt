package com.minibot.control.tool

import com.minibot.control.container.ContainerActivity
import com.minibot.control.container.ContainerInstance
import com.minibot.control.container.ContainerKind
import com.minibot.control.container.ContainerPhase
import com.minibot.control.container.ContainerProfile
import com.minibot.control.container.ContainerService
import com.minibot.control.container.ContainerSummary
import com.minibot.control.container.CreateContainerSpec
import com.minibot.control.container.DeployContainerRequest
import com.minibot.control.container.DockerContainerState
import com.minibot.control.container.DockerEngine
import com.minibot.control.container.DockerExecResult
import com.minibot.control.container.ResourcePolicy
import com.minibot.control.session.EventHub
import com.minibot.control.session.EventStore
import com.minibot.control.session.FakeSessionStore
import com.minibot.control.session.SessionRecord
import com.minibot.control.session.TurnRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ToolExecutorTest {
    @Test
    fun `safe command writes stdout log and emits events`() = runBlocking {
        val fixture = fixture()
        val result = fixture.executor.execute(
            fixture.turn,
            fixture.session,
            request("echo hello", "/workspace"),
        )
        assertEquals(0, result.exitCode)
        assertEquals("hello\n", result.stdoutPreview)
        assertNotNull(result.outputRef)
        assertTrue(Files.exists(Path.of(result.outputRef!!)))
        assertEquals("/workspace", fixture.engine.cwd)
        assertTrue(fixture.sessionStore.events().any { it.type == "tool.started" })
        assertTrue(fixture.sessionStore.events().any { it.type == "tool.completed" })
    }

    @Test
    fun `cwd outside workspace is rejected before started`() = runBlocking {
        val fixture = fixture()
        val result = fixture.executor.execute(
            fixture.turn,
            fixture.session,
            request("pwd", "/workspaceX"),
        )
        assertEquals("invalid_cwd", result.errorCode)
        assertEquals(0, fixture.engine.execCalls)
        assertTrue(fixture.sessionStore.events().any { it.type == "tool.failed" })
        assertFalse(fixture.sessionStore.events().any { it.type == "tool.started" })
    }

    @Test
    fun `danger command returns pending approval then executes after approval`() = runBlocking {
        val fixture = fixture(approvalTimeoutMs = 5_000)
        val toolRequest = request("rm -rf /tmp/demo", "/workspace")
        val pending = fixture.executor.execute(fixture.turn, fixture.session, toolRequest)
        assertTrue(pending.approvalPending)
        assertNotNull(pending.approvalId)
        assertEquals(0, fixture.engine.execCalls)

        fixture.approvals.decide(fixture.turn.id, pending.approvalId!!, "approve")
        val result = fixture.executor.executeAfterApproval(
            fixture.turn,
            fixture.session,
            toolRequest,
            pending.approvalId!!,
        )
        assertEquals(0, result.exitCode)
        assertEquals(1, fixture.engine.execCalls)
    }

    @Test
    fun `danger command expires without executing`() = runBlocking {
        val fixture = fixture(approvalTimeoutMs = 50)
        val toolRequest = request("rm -rf /tmp/demo", "/workspace")
        val pending = fixture.executor.execute(fixture.turn, fixture.session, toolRequest)
        assertTrue(pending.approvalPending)
        Thread.sleep(60)
        fixture.approvals.expireDue()
        val result = fixture.executor.executeAfterApproval(
            fixture.turn,
            fixture.session,
            toolRequest,
            pending.approvalId!!,
        )
        assertTrue(result.denied)
        assertEquals("approval_expired", result.errorCode)
        assertEquals(0, fixture.engine.execCalls)
    }

    private fun request(command: String, cwd: String?) = ToolRequest(
        callId = "call-1",
        turnId = TURN_ID,
        sessionId = SESSION_ID,
        tool = "shell.exec",
        command = command,
        cwd = cwd,
        timeoutMs = 120_000,
    )

    private fun fixture(approvalTimeoutMs: Long = 5_000): Fixture {
        val sessionStore = FakeSessionStore()
        val session = sessionStore.createSession(PROFILE_ID, null, "session", null)
        val turn = sessionStore.createTurn(session.id, "client-1", "test", "{}").turn
        val eventStore = EventStore(sessionStore, EventHub())
        val approvalStore = InMemoryApprovalStore()
        val approvals = ApprovalService(approvalStore, eventStore)
        val engine = FakeDockerEngine()
        val containers = FakeContainerService()
        val dataDir = Files.createTempDirectory("minibot-tool-test")
        val executor = DockerToolExecutor(
            engine = engine,
            containers = containers,
            events = eventStore,
            approvals = approvals,
            dataDir = dataDir,
            approvalTimeoutMs = approvalTimeoutMs,
        )
        return Fixture(sessionStore, session, turn, engine, approvalStore, approvals, executor)
    }

    private data class Fixture(
        val sessionStore: FakeSessionStore,
        val session: SessionRecord,
        val turn: TurnRecord,
        val engine: FakeDockerEngine,
        val approvalStore: InMemoryApprovalStore,
        val approvals: ApprovalService,
        val executor: DockerToolExecutor,
    )

    private companion object {
        const val PROFILE_ID = "11111111-1111-1111-1111-111111111111"
        const val SESSION_ID = "session-1"
        const val TURN_ID = "turn-1"
    }
}

private class FakeDockerEngine : DockerEngine {
    var execCalls = 0
    var cwd: String? = null

    override fun exec(
        containerId: String,
        command: String,
        cwd: String?,
        timeoutMs: Long,
        onChunk: (stream: String, chunk: ByteArray) -> Unit,
    ): DockerExecResult {
        execCalls++
        this.cwd = cwd
        onChunk("stdout", "hello\n".toByteArray())
        return DockerExecResult(0, 10, false)
    }

    override fun pullImage(imageRef: String) = Unit
    override fun createContainer(spec: CreateContainerSpec): String = "container-1"
    override fun startContainer(containerId: String) = Unit
    override fun stopContainer(containerId: String, timeoutSeconds: Int) = Unit
    override fun removeContainer(containerId: String, removeVolumes: Boolean) = Unit
    override fun inspectContainer(containerId: String): DockerContainerState? = null
    override fun listManagedContainers(labelKey: String, labelValue: String) = emptyList<DockerContainerState>()
}

private class FakeContainerService : ContainerService {
    override fun ensureRunning(profileId: String): ContainerSummary {
        val profile = ContainerProfile(
            id = profileId,
            kind = ContainerKind.CLOUD,
            name = "cloud",
            imageRef = "minibot/runtime:test",
            imageDigest = null,
            resourcePolicy = ResourcePolicy(),
            components = emptyList(),
            packages = emptyList(),
            createdAt = Instant.now().toString(),
        )
        return ContainerSummary(
            profile = profile,
            instance = ContainerInstance(
                id = "instance-1",
                profileId = profileId,
                containerId = "container-1",
                phase = ContainerPhase.RUNNING,
                pinned = false,
                lastActivityAt = Instant.now().toString(),
                lastError = null,
            ),
            activity = ContainerActivity(),
        )
    }

    override fun deploy(request: DeployContainerRequest): ContainerSummary = ensureRunning("11111111-1111-1111-1111-111111111111")
    override fun list() = emptyList<ContainerSummary>()
    override fun get(profileId: String) = ensureRunning(profileId)
    override fun stop(profileId: String) = ensureRunning(profileId)
    override fun deleteInstance(profileId: String) = ensureRunning(profileId)
    override fun archive(profileId: String, confirm: Boolean) = ensureRunning(profileId)
    override fun setPinned(profileId: String, pinned: Boolean) = ensureRunning(profileId)
    override fun reconcile() = Unit
}

private class InMemoryApprovalStore : ApprovalStore {
    private val records = mutableMapOf<String, ApprovalRecord>()

    override fun create(turnId: String, sessionId: String, requestJson: String, expiresAt: String): ApprovalRecord {
        val record = ApprovalRecord(
            id = UUID.randomUUID().toString(),
            turnId = turnId,
            sessionId = sessionId,
            status = ApprovalStatus.PENDING,
            requestJson = requestJson,
            decision = null,
            reason = null,
            expiresAt = expiresAt,
            decidedAt = null,
            createdAt = Instant.now().toString(),
        )
        records[record.id] = record
        return record
    }

    override fun find(approvalId: String): ApprovalRecord? = records[approvalId]
    override fun listPending(): List<ApprovalRecord> = records.values.filter { it.status == ApprovalStatus.PENDING }

    override fun decide(approvalId: String, status: ApprovalStatus, decision: String?, reason: String?): ApprovalRecord? {
        val current = records[approvalId] ?: return null
        if (current.status != ApprovalStatus.PENDING) return current
        return current.copy(
            status = status,
            decision = decision,
            reason = reason,
            decidedAt = Instant.now().toString(),
        ).also { records[approvalId] = it }
    }

    override fun markExpired(approvalId: String): ApprovalRecord? =
        decide(approvalId, ApprovalStatus.EXPIRED, "expired", null)
}
