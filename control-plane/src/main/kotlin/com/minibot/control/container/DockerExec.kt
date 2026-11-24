package com.minibot.control.container

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.ExecCreateCmdResponse
import com.github.dockerjava.core.command.ExecStartResultCallback
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun DockerClient.execInContainer(
    containerId: String,
    command: String,
    cwd: String?,
    timeoutMs: Long,
    onChunk: (stream: String, chunk: ByteArray) -> Unit,
): DockerExecResult {
    val startedAt = System.currentTimeMillis()
    val pidFile = "/tmp/minibot-exec-${UUID.randomUUID()}.pid"
    val inner = "echo \$\$ > ${shellQuote(pidFile)}; exec sh -lc ${shellQuote(command)}"

    // Run the command as the exec shell itself so its pid is also the process
    // group leader. `setsid` may fork and exit immediately, which closes the
    // exec stream while the command keeps running and breaks timeout handling.
    val create = execCreateCmd(containerId)
        .withAttachStdout(true)
        .withAttachStderr(true)
        .withCmd("sh", "-lc", inner)
    cwd?.let { create.withWorkingDir(it) }
    val execId = create.exec().id

    val callback = ExecStartResultCallback(
        StreamOutput("stdout", onChunk),
        StreamOutput("stderr", onChunk),
    )
    var timedOut = false
    try {
        execStartCmd(execId).withDetach(false).withTty(false).exec(callback)
        val completed = try {
            callback.awaitCompletion(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }
        if (!completed) {
            timedOut = true
            runCatching { killProcess(containerId, pidFile) }
        }
    } finally {
        runCatching { callback.close() }
    }

    val exitCode = if (timedOut) {
        124
    } else {
        runCatching { inspectExecCmd(execId).exec().exitCode }
            .getOrNull()
            ?.toInt()
            ?: 0
    }
    return DockerExecResult(
        exitCode = exitCode,
        durationMs = System.currentTimeMillis() - startedAt,
        timedOut = timedOut,
    )
}

private fun DockerClient.killProcess(containerId: String, pidFile: String) {
    val pid = "$(cat ${shellQuote(pidFile)} 2>/dev/null)"
    val command = """
        kill -TERM -$pid 2>/dev/null || kill -TERM $pid 2>/dev/null
        sleep 1
        kill -KILL -$pid 2>/dev/null || kill -KILL $pid 2>/dev/null
    """.trimIndent()
    runCatching {
        val create = execCreateCmd(containerId)
            .withAttachStdout(false)
            .withAttachStderr(false)
            .withCmd("sh", "-lc", command)
            .exec()
        val callback = object : ExecStartResultCallback(
            ByteArrayOutputStream(),
            ByteArrayOutputStream(),
        ) {}
        execStartCmd(create.id).withDetach(false).withTty(false).exec(callback)
        callback.awaitCompletion(5, TimeUnit.SECONDS)
        callback.close()
    }
}

private class StreamOutput(
    private val stream: String,
    private val onChunk: (stream: String, chunk: ByteArray) -> Unit,
) : OutputStream() {
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        onChunk(stream, b.copyOfRange(off, off + len))
    }

    override fun write(b: Int) {
        onChunk(stream, byteArrayOf(b.toByte()))
    }
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\\''") + "'"
}
