package com.minibot.control.tool

import com.minibot.control.container.ContainerException
import com.minibot.control.container.ContainerService
import com.minibot.control.container.DockerEngine
import com.minibot.control.session.EventStore
import com.minibot.control.session.SessionRecord
import com.minibot.control.session.TurnRecord
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

interface ToolExecutor {
    suspend fun execute(turn: TurnRecord, session: SessionRecord, request: ToolRequest): ToolResult

    suspend fun executeAfterApproval(
        turn: TurnRecord,
        session: SessionRecord,
        request: ToolRequest,
        approvalId: String,
    ): ToolResult
}

class DockerToolExecutor(
    private val engine: DockerEngine,
    private val containers: ContainerService,
    private val events: EventStore,
    private val approvals: ApprovalService,
    private val dataDir: Path,
    private val approvalTimeoutMs: Long = 600_000L,
    private val maxExecPerContainer: Int = 4,
) : ToolExecutor {
    private val permits = ConcurrentHashMap<String, Semaphore>()
    private val logRoot = dataDir.resolve("volumes").toAbsolutePath().normalize()

    override suspend fun execute(
        turn: TurnRecord,
        session: SessionRecord,
        request: ToolRequest,
    ): ToolResult = executeInternal(turn, session, request, approvalId = null)

    override suspend fun executeAfterApproval(
        turn: TurnRecord,
        session: SessionRecord,
        request: ToolRequest,
        approvalId: String,
    ): ToolResult = executeInternal(turn, session, request, approvalId)

    private suspend fun executeInternal(
        turn: TurnRecord,
        session: SessionRecord,
        request: ToolRequest,
        approvalId: String?,
    ): ToolResult {
        val startedAt = System.currentTimeMillis()
        val risk = ShellRiskClassifier.classify(request.command)
        val timeoutMs = request.timeoutMs.coerceIn(1_000L, 600_000L)
        try {
            validate(request)
        } catch (error: ToolException) {
            val result = failedResult(request.callId, startedAt, error.code)
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "tool.failed",
                payloadJson = failurePayload(request, result),
            )
            return result
        }
        val commandPreview = request.command.take(32_768)
        if (approvalId == null) {
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "tool.started",
                payloadJson = buildJsonObject {
                    put("callId", request.callId)
                    put("tool", request.tool)
                    put("command", commandPreview)
                    put("cwd", request.cwd ?: "/workspace")
                    put("risk", risk.name)
                    put("timeoutMs", timeoutMs)
                }.toString(),
            )
        }

        var createdApproval: ApprovalRecord? = null
        val approvalStatus = if (approvalId != null) {
            val approval = approvals.find(approvalId)
                ?: throw ToolException("approval_not_found", "Approval not found", 404)
            if (approval.turnId != turn.id) {
                throw ToolException("approval_turn_mismatch", "Approval belongs to another turn", 409)
            }
            approval.status
        } else if (risk == ToolRisk.DANGER) {
            createdApproval = approvals.request(
                turnId = turn.id,
                sessionId = session.id,
                request = ToolApprovalRequest(
                    callId = request.callId,
                    tool = request.tool,
                    command = commandPreview,
                    risk = risk,
                    timeoutMs = timeoutMs,
                ),
                timeoutMs = approvalTimeoutMs,
                waitForDecision = false,
            )
            createdApproval.status
        } else {
            null
        }
        if (approvalStatus != null && approvalStatus != ApprovalStatus.APPROVED) {
            val pending = approvalStatus == ApprovalStatus.PENDING
            if (!pending) {
                events.append(
                    sessionId = session.id,
                    turnId = turn.id,
                    messageId = null,
                    type = "tool.cancelled",
                    payloadJson = buildJsonObject {
                        put("callId", request.callId)
                        put("reason", "approval_${approvalStatus.name.lowercase()}")
                    }.toString(),
                )
            }
            return ToolResult(
                callId = request.callId,
                exitCode = null,
                durationMs = System.currentTimeMillis() - startedAt,
                stdoutPreview = "",
                stderrPreview = "",
                truncated = false,
                outputRef = null,
                denied = !pending,
                errorCode = if (pending) "approval_pending" else "approval_${approvalStatus.name.lowercase()}",
                approvalPending = pending,
                approvalId = approvalId ?: createdApproval?.id,
            )
        }

        val summary = try {
            containers.ensureRunning(session.profileId)
        } catch (error: ContainerException) {
            val result = failedResult(request.callId, startedAt, error.error.code)
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "tool.failed",
                payloadJson = failurePayload(request, result),
            )
            return result
        }
        val containerId = summary.instance.containerId
            ?: return failedResult(request.callId, startedAt, "container_unavailable").also {
                events.append(
                    sessionId = session.id,
                    turnId = turn.id,
                    messageId = null,
                    type = "tool.failed",
                    payloadJson = failurePayload(request, it),
                )
            }

        val semaphore = permits.computeIfAbsent(session.profileId) {
            Semaphore(maxExecPerContainer)
        }
        semaphore.acquire()
        var output: ToolOutput? = null
        try {
            val logDir = logDir(session.profileId, turn.id)
            output = ToolOutput(
                events = events,
                sessionId = session.id,
                turnId = turn.id,
                callId = request.callId,
                logDir = logDir,
            )
            val execResult = engine.exec(
                containerId = containerId,
                command = request.command,
                cwd = request.cwd ?: "/workspace",
                timeoutMs = timeoutMs,
            ) { stream, chunk ->
                output.accept(stream, chunk)
            }
            output.flushAll()
            val result = ToolResult(
                callId = request.callId,
                exitCode = execResult.exitCode,
                durationMs = execResult.durationMs,
                stdoutPreview = output.stdoutPreview,
                stderrPreview = output.stderrPreview,
                truncated = output.truncated,
                outputRef = output.outputRef,
                timedOut = execResult.timedOut,
                errorCode = if (execResult.timedOut) "tool_timeout" else if (execResult.exitCode == 0) null else "tool_exit_nonzero",
            )
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = if (result.errorCode == null) "tool.completed" else "tool.failed",
                payloadJson = resultPayload(request, result),
            )
            return result
        } catch (error: Exception) {
            output?.flushAll()
            val result = failedResult(request.callId, startedAt, "tool_execution_failed")
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "tool.failed",
                payloadJson = failurePayload(request, result),
            )
            return result
        } finally {
            output?.close()
            semaphore.release()
        }
    }

    private fun validate(request: ToolRequest) {
        if (request.tool != "shell.exec") {
            throw ToolException("unsupported_tool", "Unsupported tool: ${request.tool}")
        }
        if (request.command.isBlank() || request.command.length > 32_768) {
            throw ToolException("invalid_command", "Command must be between 1 and 32768 characters")
        }
        val cwd = request.cwd ?: "/workspace"
        if ((cwd != "/workspace" && !cwd.startsWith("/workspace/")) || cwd.split('/').any { it == ".." }) {
            throw ToolException("invalid_cwd", "cwd must stay inside /workspace")
        }
        if (request.timeoutMs !in 1_000L..600_000L) {
            throw ToolException("invalid_timeout", "timeoutMs must be between 1 and 600 seconds")
        }
    }

    private fun failedResult(callId: String, startedAt: Long, code: String): ToolResult {
        return ToolResult(
            callId = callId,
            exitCode = null,
            durationMs = System.currentTimeMillis() - startedAt,
            stdoutPreview = "",
            stderrPreview = "",
            truncated = false,
            outputRef = null,
            errorCode = code,
        )
    }

    private fun logDir(profileId: String, turnId: String): Path {
        val profile = UUID.fromString(profileId).toString()
        val turn = runCatching { UUID.fromString(turnId).toString() }.getOrElse {
            turnId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        }
        val dir = logRoot.resolve(profile).resolve("tool-logs").resolve(turn).normalize()
        require(dir.startsWith(logRoot)) { "Refusing to write tool logs outside volumes" }
        Files.createDirectories(dir)
        return dir
    }

    private fun failurePayload(request: ToolRequest, result: ToolResult): String {
        return buildJsonObject {
            put("callId", request.callId)
            put("errorCode", result.errorCode ?: "tool_failed")
            put("durationMs", result.durationMs)
        }.toString()
    }

    private fun resultPayload(request: ToolRequest, result: ToolResult): String {
        return buildJsonObject {
            put("callId", request.callId)
            put("exitCode", result.exitCode ?: -1)
            put("durationMs", result.durationMs)
            put("timedOut", result.timedOut)
            put("truncated", result.truncated)
            put("outputRef", result.outputRef ?: "")
            put("stdoutPreview", result.stdoutPreview.take(32_768))
            put("stderrPreview", result.stderrPreview.take(16_384))
        }.toString()
    }
}

class ShellRiskClassifier {
    companion object {
        private val dangerPatterns = listOf(
            Regex("""rm\s+-[a-zA-Z]*r[a-zA-Z]*f"""),
            Regex("""rm\s+-[a-zA-Z]*f[a-zA-Z]*r"""),
            Regex("""mkfs|mke2fs|dd\s+if=|dd\s+of="""),
            Regex("""shutdown|reboot|poweroff|halt"""),
            Regex("""chmod\s+-R\s+777\s+/"""),
            Regex("""sudo|su\s+-"""),
            Regex("""docker\s+|podman\s+"""),
            Regex("""mount\s+|umount\s+"""),
            Regex("""iptables|nft\s+|systemctl|service\s+"""),
            Regex("""kill\s+-9|pkill\s+-9"""),
            Regex("""curl\s+.*\|\s*sh|wget\s+.*\|\s*sh"""),
            Regex("""/etc/(shadow|passwd)|/proc/|/sys/"""),
            Regex("""nc\s+|socat\s+"""),
            Regex(""">\s*/dev/sd|>\s*/dev/nvme"""),
            Regex("""useradd|userdel|passwd\s+"""),
        )

        fun classify(command: String): ToolRisk {
            return if (dangerPatterns.any { it.containsMatchIn(command) }) {
                ToolRisk.DANGER
            } else {
                ToolRisk.SAFE
            }
        }
    }
}

private class ToolOutput(
    private val events: EventStore,
    private val sessionId: String,
    private val turnId: String,
    private val callId: String,
    logDir: Path,
) {
    private val previewLimit = 64 * 1024
    private val stdoutFile = logDir.resolve("${safeName(callId)}.stdout.log")
    private val stderrFile = logDir.resolve("${safeName(callId)}.stderr.log")
    private val stdoutWriter: BufferedWriter = Files.newBufferedWriter(
        stdoutFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
    )
    private val stderrWriter: BufferedWriter = Files.newBufferedWriter(
        stderrFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
    )
    private val stdoutPreviewBuilder = StringBuilder()
    private val stderrPreviewBuilder = StringBuilder()
    private val stdoutPending = StringBuilder()
    private val stderrPending = StringBuilder()
    private var stdoutChars = 0L
    private var stderrChars = 0L
    private var lastFlushAt = System.currentTimeMillis()
    private var stdoutTruncated = false
    private var stderrTruncated = false

    val stdoutPreview: String
        get() = stdoutPreviewBuilder.toString()
    val stderrPreview: String
        get() = stderrPreviewBuilder.toString()
    val truncated: Boolean
        get() = stdoutTruncated || stderrTruncated
    val outputRef: String
        get() = "${stdoutFile.toAbsolutePath()}"

    fun accept(stream: String, chunk: ByteArray) {
        val text = chunk.toString(Charsets.UTF_8)
        if (stream == "stderr") {
            synchronized(stderrWriter) {
                stderrWriter.write(text)
                stderrChars += text.length
                appendPreview(stderrPreviewBuilder, text) { stderrTruncated = true }
                stderrPending.append(text)
                if (stderrPending.length >= 4_096 || System.currentTimeMillis() - lastFlushAt >= 50) {
                    flush("stderr", stderrPending)
                }
            }
        } else {
            synchronized(stdoutWriter) {
                stdoutWriter.write(text)
                stdoutChars += text.length
                appendPreview(stdoutPreviewBuilder, text) { stdoutTruncated = true }
                stdoutPending.append(text)
                if (stdoutPending.length >= 4_096 || System.currentTimeMillis() - lastFlushAt >= 50) {
                    flush("stdout", stdoutPending)
                }
            }
        }
    }

    fun flushAll() {
        flush("stdout", stdoutPending)
        flush("stderr", stderrPending)
    }

    fun close() {
        runCatching { stdoutWriter.close() }
        runCatching { stderrWriter.close() }
    }

    private fun flush(stream: String, pending: StringBuilder) {
        while (pending.length >= 4_096) {
            val chunk = pending.substring(0, 4_096)
            pending.delete(0, 4_096)
            appendEvent(stream, chunk)
        }
        if (pending.isNotEmpty()) {
            val chunk = pending.toString()
            pending.clear()
            appendEvent(stream, chunk)
        }
        lastFlushAt = System.currentTimeMillis()
    }

    private fun appendEvent(stream: String, chunk: String) {
        events.append(
            sessionId = sessionId,
            turnId = turnId,
            messageId = null,
            type = "tool.output",
            payloadJson = buildJsonObject {
                put("callId", callId)
                put("stream", stream)
                put("chunk", chunk)
                put("offset", if (stream == "stderr") stderrChars else stdoutChars)
                put("truncated", if (stream == "stderr") stderrTruncated else stdoutTruncated)
            }.toString(),
        )
    }

    private fun appendPreview(target: StringBuilder, text: String, markTruncated: () -> Unit) {
        if (target.length >= previewLimit) {
            markTruncated()
            return
        }
        val remaining = previewLimit - target.length
        if (text.length > remaining) {
            target.append(text, 0, remaining)
            markTruncated()
        } else {
            target.append(text)
        }
    }

    private fun safeName(value: String): String {
        return value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(64)
            .ifBlank { "tool" }
    }
}
