package com.minibot.control.session

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class EventHub {
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArraySet<Channel<StoredEvent>>>()

    fun subscribe(sessionId: String): Channel<StoredEvent> {
        val channel = Channel<StoredEvent>(capacity = 256)
        subscribers.computeIfAbsent(sessionId) { CopyOnWriteArraySet() }.add(channel)
        return channel
    }

    fun unsubscribe(sessionId: String, channel: Channel<StoredEvent>) {
        val channels = subscribers[sessionId] ?: return
        channels.remove(channel)
        channel.close()
        if (channels.isEmpty()) subscribers.remove(sessionId)
    }

    fun publish(events: List<StoredEvent>) {
        events.forEach { event ->
            subscribers[event.sessionId]?.forEach { channel ->
                channel.trySend(event)
            }
        }
    }
}

class EventStore(
    private val store: SessionStore,
    private val hub: EventHub,
) {
    fun append(
        sessionId: String,
        turnId: String?,
        messageId: String?,
        type: String,
        payloadJson: String = "{}",
    ): StoredEvent {
        val event = store.appendEvent(sessionId, turnId, messageId, type, payloadJson)
        hub.publish(listOf(event))
        return event
    }

    fun appendBatch(sessionId: String, events: List<NewEvent>): List<StoredEvent> {
        val stored = store.appendEvents(sessionId, events)
        hub.publish(stored)
        return stored
    }

    fun publish(events: List<StoredEvent>) {
        hub.publish(events)
    }

    fun list(sessionId: String, afterSeq: Long, limit: Int): List<StoredEvent> {
        return store.listEvents(sessionId, afterSeq, limit)
    }
}
