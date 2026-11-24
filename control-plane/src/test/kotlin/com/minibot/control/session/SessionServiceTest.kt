package com.minibot.control.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionServiceTest {
    private val profileId = "00000000-0000-0000-0000-000000000001"
    private val store = FakeSessionStore()
    private val containers = FakeContainerService()
    private val events = EventStore(store, EventHub())
    private val enqueued = mutableListOf<TurnRecord>()
    private val enqueuer = object : TurnEnqueuer {
        override fun enqueue(turn: TurnRecord) {
            enqueued += turn
        }
    }
    private val service = SessionService(store, containers, enqueuer, events)

    @Test
    fun `create session and turn stores message and events`() {
        val session = service.createSession(
            CreateSessionRequest(profileId = profileId, title = "  "),
        )
        assertEquals("新会话", session.title)
        assertNotNull(session.workspaceId)

        val creation = service.createTurn(
            session.id,
            CreateTurnRequest(
                clientMessageId = "client-1",
                content = "你好",
                attachments = emptyList(),
            ),
        )
        assertEquals(TurnStatus.QUEUED, creation.turn.status)
        assertEquals(1L, creation.turn.createdSeq)
        assertEquals(1, store.listTurns(session.id).size)
        assertEquals(1, store.latestMessages(session.id, 100).size)
        assertEquals(1, enqueued.size)
        assertEquals("turn.created", store.listEvents(session.id, 0, 10).first().type)
    }

    @Test
    fun `duplicate clientMessageId returns the same turn`() {
        val session = service.createSession(CreateSessionRequest(profileId, "Test"))
        val first = service.createTurn(
            session.id,
            CreateTurnRequest("client-1", "你好"),
        )
        val second = service.createTurn(
            session.id,
            CreateTurnRequest("client-1", "你好"),
        )

        assertEquals(first.turn.id, second.turn.id)
        assertEquals(1, enqueued.size)
        assertTrue(second.events.isEmpty())
    }

    @Test
    fun `cancel queued turn writes interrupted event`() {
        val session = service.createSession(CreateSessionRequest(profileId, "Test"))
        val creation = service.createTurn(session.id, CreateTurnRequest("client-1", "你好"))
        val cancelled = service.cancelTurn(session.id, creation.turn.id)

        assertEquals(TurnStatus.CANCELLED, cancelled.status)
        assertTrue(store.listEvents(session.id, 0, 100).any { it.type == "turn.interrupted" })
    }
}
