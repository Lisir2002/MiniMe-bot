package com.minibot.control.session

import com.minibot.control.model.ModelException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

class TurnSchedulerTest {
    @Test
    fun `same session turns run in queue order`() = runBlocking {
        val store = FakeSessionStore()
        val session = store.createSession("profile-1", "workspace-1", "Test")
        val first = store.createTurn(session.id, "client-1", "first", "{}").turn
        val second = store.createTurn(session.id, "client-2", "second", "{}").turn
        val events = EventStore(store, EventHub())
        val containers = FakeContainerService()
        val executor = RecordingExecutor()
        val scheduler = TurnScheduler(store, events, containers, executor, maxConcurrentTurns = 2)
        try {
            scheduler.start()
            waitForStatus(store, first.id, TurnStatus.COMPLETED)
            waitForStatus(store, second.id, TurnStatus.COMPLETED)

            assertEquals(listOf(first.id, second.id), executor.visited)
            assertEquals(2, containers.ensureCalls.get())
            assertTrue(store.listEvents(session.id, 0, 100).any { it.type == "turn.completed" })
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `different sessions can run concurrently`() = runBlocking {
        val store = FakeSessionStore()
        val firstSession = store.createSession("profile-1", "workspace-1", "One")
        val secondSession = store.createSession("profile-2", "workspace-2", "Two")
        val first = store.createTurn(firstSession.id, "client-1", "first", "{}").turn
        val second = store.createTurn(secondSession.id, "client-2", "second", "{}").turn
        val events = EventStore(store, EventHub())
        val containers = FakeContainerService()
        val bothStarted = CompletableDeferred<Unit>()
        val started = AtomicInteger(0)
        val executor = object : TurnExecutor {
            override suspend fun execute(turn: TurnRecord, session: SessionRecord) {
                if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                withTimeout(2_000) { bothStarted.await() }
            }
        }
        val scheduler = TurnScheduler(store, events, containers, executor, maxConcurrentTurns = 2)
        try {
            scheduler.start()
            withTimeout(2_000) { bothStarted.await() }
            waitForStatus(store, first.id, TurnStatus.COMPLETED)
            waitForStatus(store, second.id, TurnStatus.COMPLETED)
            assertEquals(2, started.get())
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `queued turn can be cancelled before execution`() = runBlocking {
        val store = FakeSessionStore()
        val session = store.createSession("profile-1", "workspace-1", "Test")
        val turn = store.createTurn(session.id, "client-1", "first", "{}").turn
        val cancelled = store.cancelQueuedTurn(session.id, turn.id)

        assertEquals(TurnStatus.CANCELLED, cancelled?.status)
    }

    @Test
    fun `model error is surfaced as turn error code`() = runBlocking {
        val store = FakeSessionStore()
        val session = store.createSession("profile-1", "workspace-1", "Test")
        val turn = store.createTurn(session.id, "client-1", "first", "{}").turn
        val events = EventStore(store, EventHub())
        val executor = object : TurnExecutor {
            override suspend fun execute(turn: TurnRecord, session: SessionRecord) {
                throw ModelException("binding_unavailable", "This session has no model binding", 409)
            }
        }
        val scheduler = TurnScheduler(store, events, FakeContainerService(), executor, maxConcurrentTurns = 1)
        try {
            scheduler.start()
            waitForStatus(store, turn.id, TurnStatus.FAILED)
            assertEquals("binding_unavailable", store.findTurn(turn.id)?.errorCode)
            waitForEvent(store, session.id, "turn.failed")
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `suspended turn releases worker and waits for approval`() = runBlocking {
        val store = FakeSessionStore()
        val session = store.createSession("profile-1", "workspace-1", "Test")
        val turn = store.createTurn(session.id, "client-1", "first", "{}").turn
        val events = EventStore(store, EventHub())
        val executor = object : TurnExecutor {
            override suspend fun execute(turn: TurnRecord, session: SessionRecord) {
                throw TurnSuspended(turn.id, "approval-1")
            }
        }
        val scheduler = TurnScheduler(store, events, FakeContainerService(), executor, maxConcurrentTurns = 1)
        try {
            scheduler.start()
            waitForStatus(store, turn.id, TurnStatus.WAITING_APPROVAL)
            waitForEvent(store, session.id, "turn.waiting_approval")
        } finally {
            scheduler.close()
        }
    }

    private suspend fun waitForEvent(store: FakeSessionStore, sessionId: String, type: String) {
        withTimeout(5_000) {
            while (store.listEvents(sessionId, 0, 100).none { it.type == type }) {
                delay(10)
            }
        }
    }

    private suspend fun waitForStatus(store: FakeSessionStore, turnId: String, status: TurnStatus) {
        withTimeout(5_000) {
            while (store.findTurn(turnId)?.status != status) {
                delay(10)
            }
        }
    }

    private class RecordingExecutor : TurnExecutor {
        val visited = mutableListOf<String>()

        override suspend fun execute(turn: TurnRecord, session: SessionRecord) {
            visited += turn.id
            delay(10)
        }
    }
}
