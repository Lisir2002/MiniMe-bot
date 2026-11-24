package com.minibot.control.session

import com.minibot.control.container.ContainerException
import com.minibot.control.container.ContainerService
import com.minibot.control.model.ModelException
import com.minibot.control.tool.ApprovalStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface TurnEnqueuer {
    fun enqueue(turn: TurnRecord)
}

class TurnScheduler(
    private val store: SessionStore,
    private val events: EventStore,
    private val containers: ContainerService,
    private val executor: TurnExecutor,
    maxConcurrentTurns: Int = 4,
) : AutoCloseable, TurnEnqueuer {
    private val logger = LoggerFactory.getLogger(TurnScheduler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workers = ConcurrentHashMap<String, Channel<String>>()
    private val semaphore = Semaphore(maxConcurrentTurns)
    private val started = AtomicBoolean(false)
    var approvalStatus: ((String) -> ApprovalStatus?)? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val interrupted = runCatching { store.markRunningTurnsInterrupted() }
            .getOrElse {
                logger.warn("Failed to recover interrupted turns", it)
                emptyList()
            }
        interrupted.forEach { turn ->
            events.append(
                sessionId = turn.sessionId,
                turnId = turn.id,
                messageId = null,
                type = "turn.interrupted",
                payloadJson = buildJsonObject {
                    put("reason", "control_plane_restarted")
                }.toString(),
            )
        }
        store.listQueuedTurns().forEach(::enqueue)
    }

    override fun enqueue(turn: TurnRecord) {
        if (turn.status != TurnStatus.QUEUED) return
        workerFor(turn.sessionId).trySend(turn.id)
    }

    override fun close() {
        workers.values.forEach { it.close() }
        workers.clear()
        scope.cancel()
    }

    private fun workerFor(sessionId: String): Channel<String> {
        return workers.computeIfAbsent(sessionId) {
            val channel = Channel<String>(capacity = Channel.UNLIMITED)
            scope.launch {
                for (turnId in channel) {
                    semaphore.withPermit {
                        process(turnId)
                    }
                }
            }
            channel
        }
    }

    private suspend fun process(turnId: String) {
        val turn = store.findTurn(turnId) ?: return
        if (turn.status != TurnStatus.QUEUED && turn.status != TurnStatus.WAITING_RUNTIME) return
        val session = store.findSession(turn.sessionId) ?: run {
            fail(turn, "session_not_found", "Session not found", null)
            return
        }

        store.updateTurnStatus(turn.id, TurnStatus.WAITING_RUNTIME)
        try {
            containers.ensureRunning(session.profileId)
            store.updateTurnStatus(turn.id, TurnStatus.RUNNING)
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "turn.started",
                payloadJson = "{}",
            )
            executor.execute(turn, session)
            store.updateTurnStatus(turn.id, TurnStatus.COMPLETED)
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "turn.completed",
                payloadJson = "{}",
            )
        } catch (suspended: TurnSuspended) {
            store.updateTurnStatus(turn.id, TurnStatus.WAITING_APPROVAL)
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "turn.waiting_approval",
                payloadJson = buildJsonObject {
                    put("approvalId", suspended.approvalId ?: "")
                }.toString(),
            )
            resumeIfApprovalSettled(turn, suspended.approvalId)
        } catch (cancelled: CancellationException) {
            store.updateTurnStatus(turn.id, TurnStatus.INTERRUPTED, "turn_cancelled", "Turn cancelled")
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "turn.interrupted",
                payloadJson = buildJsonObject { put("reason", "scheduler_cancelled") }.toString(),
            )
            throw cancelled
        } catch (error: ContainerException) {
            fail(turn, error.error.code, error.error.message)
        } catch (error: ModelException) {
            fail(turn, error.code, error.message ?: "Model execution failed")
        } catch (error: Exception) {
            logger.error("Turn execution failed: {}", turn.id, error)
            fail(turn, "agent_execution_failed", error.message ?: error::class.simpleName ?: "unknown")
        }
    }

    private fun resumeIfApprovalSettled(turn: TurnRecord, approvalId: String?) {
        if (approvalId == null) return
        val status = approvalStatus?.invoke(approvalId) ?: return
        if (status == ApprovalStatus.PENDING) return
        if (store.findTurn(turn.id)?.status != TurnStatus.WAITING_APPROVAL) return
        val queued = store.updateTurnStatus(turn.id, TurnStatus.QUEUED) ?: return
        enqueue(queued)
    }

    private fun fail(turn: TurnRecord, code: String, message: String, cause: Throwable? = null) {
        if (cause != null) logger.error("Turn failed: {}", turn.id, cause)
        store.updateTurnStatus(turn.id, TurnStatus.FAILED, code, message)
        events.append(
            sessionId = turn.sessionId,
            turnId = turn.id,
            messageId = null,
            type = "turn.failed",
            payloadJson = buildJsonObject {
                put("code", code)
                put("message", message)
            }.toString(),
        )
    }
}
