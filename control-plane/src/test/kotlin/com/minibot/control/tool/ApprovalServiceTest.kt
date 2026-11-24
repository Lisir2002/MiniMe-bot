package com.minibot.control.tool

import com.minibot.control.session.EventHub
import com.minibot.control.session.EventStore
import com.minibot.control.session.FakeSessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ApprovalServiceTest {
    @Test
    fun `approve resolves waiter and records decision`() = runBlocking {
        val fixture = fixture()
        val pending = async {
            fixture.service.request(
                turnId = "t1",
                sessionId = fixture.sessionStore.listSessions().single().id,
                request = request(),
                timeoutMs = 5_000,
            )
        }
        repeat(100) { if (fixture.store.listPending().isNotEmpty()) return@repeat; yield() }
        val created = fixture.store.listPending().single()
        fixture.service.decide("t1", created.id, "approve")
        val record = pending.await()
        assertEquals(ApprovalStatus.APPROVED, record.status)
        assertEquals("approve", record.decision)
        assertEquals("approval.requested", fixture.sessionStore.events()[0].type)
        assertEquals("approval.resolved", fixture.sessionStore.events().last().type)
    }

    @Test
    fun `deny resolves with denial`() = runBlocking {
        val fixture = fixture()
        val pending = async {
            fixture.service.request("t1", fixture.sessionStore.listSessions().single().id, request(), 5_000)
        }
        repeat(100) { if (fixture.store.listPending().isNotEmpty()) return@repeat; yield() }
        fixture.service.decide("t1", fixture.store.listPending().single().id, "deny")
        assertEquals(ApprovalStatus.DENIED, pending.await().status)
    }

    @Test
    fun `timeout marks approval expired`() = runBlocking {
        val fixture = fixture()
        val record = fixture.service.request("t1", fixture.sessionStore.listSessions().single().id, request(), 50)
        assertEquals(ApprovalStatus.EXPIRED, record.status)
    }

    @Test
    fun `non blocking approval notifies resolution callback once`() = runBlocking {
        val fixture = fixture()
        val resolved = mutableListOf<ApprovalRecord>()
        fixture.service.onResolved = { resolved += it }
        val pending = fixture.service.request(
            "t1",
            fixture.sessionStore.listSessions().single().id,
            request(),
            5_000,
            waitForDecision = false,
        )
        assertEquals(ApprovalStatus.PENDING, pending.status)
        fixture.service.decide("t1", pending.id, "approve")
        assertEquals(1, resolved.size)
        assertEquals(ApprovalStatus.APPROVED, resolved.single().status)
    }

    @Test
    fun `expired approval cannot be decided`() = runBlocking {
        val fixture = fixture()
        val record = fixture.service.request("t1", fixture.sessionStore.listSessions().single().id, request(), 50)
        val error = try {
            fixture.service.decide("t1", record.id, "approve")
            null
        } catch (error: ApprovalException) {
            error
        }
        assertEquals("approval_expired", error?.code)
        assertEquals(409, error?.status)
    }

    @Test
    fun `repeated decision is idempotent`() = runBlocking {
        val fixture = fixture()
        val pending = async { fixture.service.request("t1", fixture.sessionStore.listSessions().single().id, request(), 5_000) }
        repeat(100) { if (fixture.store.listPending().isNotEmpty()) return@repeat; yield() }
        val id = fixture.store.listPending().single().id
        val first = fixture.service.decide("t1", id, "approve")
        val second = fixture.service.decide("t1", id, "deny")
        assertEquals(ApprovalStatus.APPROVED, first.approval.status)
        assertTrue(first.alreadyDecided.not())
        assertTrue(second.alreadyDecided)
        assertEquals(ApprovalStatus.APPROVED, pending.await().status)
    }

    private fun request() = ToolApprovalRequest(
        callId = "call-1",
        tool = "shell.exec",
        command = "rm -rf /tmp/demo",
        risk = ToolRisk.DANGER,
        timeoutMs = 120_000,
    )

    private fun fixture(): Fixture {
        val sessionStore = FakeSessionStore()
        sessionStore.createSession("p1", null, "session", null)
        val sessionId = sessionStore.listSessions().single().id
        val eventStore = EventStore(sessionStore, EventHub())
        val store = FakeApprovalStore(sessionId)
        return Fixture(store, ApprovalService(store, eventStore), sessionStore)
    }

    private data class Fixture(
        val store: FakeApprovalStore,
        val service: ApprovalService,
        val sessionStore: FakeSessionStore,
    )
}

private class FakeApprovalStore(private val sessionId: String) : ApprovalStore {
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

    override fun decide(
        approvalId: String,
        status: ApprovalStatus,
        decision: String?,
        reason: String?,
    ): ApprovalRecord? {
        val current = records[approvalId] ?: return null
        if (current.status != ApprovalStatus.PENDING) return current
        val updated = current.copy(
            status = status,
            decision = decision,
            reason = reason,
            decidedAt = Instant.now().toString(),
        )
        records[approvalId] = updated
        return updated
    }

    override fun markExpired(approvalId: String): ApprovalRecord? {
        return decide(approvalId, ApprovalStatus.EXPIRED, "expired", null)
    }
}
