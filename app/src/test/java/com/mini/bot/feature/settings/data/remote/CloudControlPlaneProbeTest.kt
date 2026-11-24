package com.mini.bot.feature.settings.data.remote

import com.mini.bot.feature.settings.domain.model.CloudControlPlaneEndpoint
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudControlPlaneProbeTest {
    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `reports healthy control plane`() = runBlocking {
        server = startServer(status = 200, body = """{"status":"ok","time":"2026-11-24T00:00:00Z"}""")
        val result = CloudControlPlaneProbe().check(endpoint())
        assertTrue(result.reachable)
    }

    @Test
    fun `reports unhealthy response`() = runBlocking {
        server = startServer(status = 500, body = """{"status":"error"}""")
        val result = CloudControlPlaneProbe().check(endpoint())
        assertFalse(result.reachable)
        assertTrue(result.statusCode == 500)
    }

    @Test
    fun `reports unreachable port`() = runBlocking {
        val result = CloudControlPlaneProbe(connectTimeoutMs = 500, readTimeoutMs = 500)
            .check(CloudControlPlaneEndpoint("http://127.0.0.1:1", "127.0.0.1", 1, secure = false))
        assertFalse(result.reachable)
    }

    private fun endpoint(): CloudControlPlaneEndpoint {
        val port = server!!.localPort
        return CloudControlPlaneEndpoint("http://127.0.0.1:$port", "127.0.0.1", port, secure = false)
    }

    private fun startServer(status: Int, body: String): ServerSocket {
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "cloud-probe-test-server") {
            runCatching {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val bytes = body.toByteArray()
                    val reason = if (status == 200) "OK" else "Error"
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write("HTTP/1.1 $status $reason\r\n")
                    writer.write("Content-Type: application/json\r\n")
                    writer.write("Content-Length: ${bytes.size}\r\n")
                    writer.write("Connection: close\r\n")
                    writer.write("\r\n")
                    writer.flush()
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            }
        }
        return serverSocket
    }
}
