package com.mini.bot.feature.settings.data.remote

import com.mini.bot.feature.settings.domain.model.CloudControlPlaneEndpoint
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudControlPlaneClientTest {
    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `pair sends token and parses device credentials`() = runBlocking {
        var request: TestRequest? = null
        server = startServer { req ->
            request = req
            TestResponse(
                status = 200,
                body = """{"deviceId":"d-1","deviceToken":"dt-1","serverId":"s-1","serverName":"Cloud"}""",
            )
        }
        val response = CloudControlPlaneClient().pair(
            endpoint = endpoint(),
            bootstrapToken = "boot-token",
            devicePublicKey = "public-key",
            deviceName = "Pixel",
        )
        assertEquals("dt-1", response.deviceToken)
        assertEquals("s-1", response.serverId)
        assertTrue(request!!.path == "/api/v1/pair/claim")
        assertTrue(request!!.body.contains("\"bootstrapToken\":\"boot-token\""))
        assertTrue(request!!.body.contains("\"devicePublicKey\":\"public-key\""))
        assertTrue(request!!.headers["content-type"].orEmpty().contains("application/json"))
    }

    @Test
    fun `pair surfaces server error code`() = runBlocking {
        server = startServer {
            TestResponse(
                status = 409,
                body = """{"code":"device_already_paired","message":"This server already has a paired device"}""",
            )
        }
        val error = runCatching {
            CloudControlPlaneClient().pair(endpoint(), "boot-token", "public-key", "Pixel")
        }.exceptionOrNull()
        assertTrue(error is CloudControlPlaneException)
        assertEquals("device_already_paired", (error as CloudControlPlaneException).code)
        assertEquals(409, error.statusCode)
    }

    @Test
    fun `authenticated requests carry bearer token and parse deployment`() = runBlocking {
        val requests = mutableListOf<TestRequest>()
        server = startServer { req ->
            requests += req
            when (req.path) {
                "/api/v1/server/info" -> TestResponse(
                    200,
                    """{"serverId":"s-1","serverName":"Cloud","paired":true,"accessMode":"ip","architecture":"arm64"}""",
                )
                "/api/v1/deploy/options" -> TestResponse(
                    200,
                    """{"images":[{"id":"alpine-arm64","displayName":"Alpine / arm64","imageRef":"ghcr.io/x:alpine-arm64","architecture":"arm64","distro":"alpine"},{"id":"debian-amd64","displayName":"Debian / amd64","imageRef":"ghcr.io/x:debian-amd64","architecture":"amd64","distro":"debian"}],"presets":[{"id":"small","displayName":"Small","resourcePolicy":{"cpuCores":1.0,"memoryMb":1024,"diskMb":10240}}]}""",
                )
                "/api/v1/containers" -> TestResponse(
                    201,
                    """{"profile":{"id":"p-1","name":"My box","imageRef":"ghcr.io/x:alpine-arm64"},"instance":{"id":"i-1","profileId":"p-1","containerId":"c-1","phase":"RUNNING"}}""",
                )
                else -> TestResponse(404, """{"code":"not_found","message":"not found"}""")
            }
        }
        val client = CloudControlPlaneClient()
        val info = client.serverInfo(endpoint(), "dt-1")
        assertEquals("arm64", info.architecture)
        val options = client.deploymentOptions(endpoint(), "dt-1")
        assertEquals(2, options.images.size)
        assertEquals(1, options.presets.size)
        val summary = client.deployContainer(
            endpoint(),
            "dt-1",
            CloudDeployRequest(
                name = "My box",
                imageRef = "ghcr.io/x:alpine-arm64",
                resourcePolicy = CloudResourcePolicy(cpuCores = 1.0, memoryMb = 1024, diskMb = 10240),
            ),
        )
        assertEquals("c-1", summary.instance.containerId)
        assertTrue(requests.all { it.headers["authorization"] == "Bearer dt-1" })
        assertTrue(requests.first { it.path == "/api/v1/containers" }.body.contains("\"imageRef\":\"ghcr.io/x:alpine-arm64\""))
    }

    @Test
    fun `unauthorized response maps to unauthorized code`() = runBlocking {
        server = startServer {
            TestResponse(401, """{"code":"unauthorized","message":"A valid device token is required"}""")
        }
        val error = runCatching {
            CloudControlPlaneClient().serverInfo(endpoint(), "bad-token")
        }.exceptionOrNull()
        assertTrue(error is CloudControlPlaneException)
        assertEquals(401, (error as CloudControlPlaneException).statusCode)
        assertEquals("unauthorized", error.code)
    }

    private fun endpoint(): CloudControlPlaneEndpoint {
        val port = server!!.localPort
        return CloudControlPlaneEndpoint("http://127.0.0.1:$port", "127.0.0.1", port, secure = false)
    }

    private data class TestRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class TestResponse(val status: Int, val body: String)

    private fun startServer(responder: (TestRequest) -> TestResponse): ServerSocket {
        val serverSocket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "cloud-client-test-server") {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                socket.use {
                    runCatching {
                        val reader = it.getInputStream().bufferedReader()
                        val requestLine = reader.readLine() ?: return@runCatching
                        val method = requestLine.substringBefore(' ')
                        val path = requestLine.substringAfter(' ').substringBefore(' ')
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val name = line.substringBefore(':').lowercase()
                            headers[name] = line.substringAfter(':').trim()
                        }
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val chars = CharArray(length)
                        var read = 0
                        while (read < length) {
                            val count = reader.read(chars, read, length - read)
                            if (count < 0) break
                            read += count
                        }
                        val body = String(chars, 0, read)

                        val response = responder(TestRequest(method, path, headers, body))
                        val bytes = response.body.toByteArray(Charsets.UTF_8)
                        val reason = when (response.status) {
                            200 -> "OK"
                            201 -> "Created"
                            401 -> "Unauthorized"
                            409 -> "Conflict"
                            else -> "Error"
                        }
                        val writer = it.getOutputStream().bufferedWriter()
                        writer.write("HTTP/1.1 ${response.status} $reason\r\n")
                        writer.write("Content-Type: application/json\r\n")
                        writer.write("Content-Length: ${bytes.size}\r\n")
                        writer.write("Connection: close\r\n")
                        writer.write("\r\n")
                        writer.flush()
                        it.getOutputStream().write(bytes)
                        it.getOutputStream().flush()
                    }
                }
            }
        }
        return serverSocket
    }
}
