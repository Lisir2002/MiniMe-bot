package com.minibot.control.model

import com.minibot.control.security.SecretVault
import com.minibot.control.session.FakeContainerService
import com.minibot.control.tool.ToolCatalog
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelGatewayTest {
    @Test
    fun `streams deltas and retries a 429 before any output`() = runBlocking {
        val attempts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            if (attempts.incrementAndGet() == 1) {
                val body = "rate limited".toByteArray()
                exchange.sendResponseHeaders(429, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                return@createContext
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val events = """
                data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}

                data: [DONE]

            """.trimIndent()
            exchange.responseBody.use { it.write(events.toByteArray()) }
        }
        server.start()
        try {
            val service = service("http://127.0.0.1:${server.address.port}/v1")
            val binding = service.resolve(binding(service), PROFILE_ID)
            val deltas = mutableListOf<String>()
            val result = gateway(service).stream(
                binding = binding,
                request = ModelCallRequest(
                    model = "gpt-4o-mini",
                    messages = listOf(ModelGatewayMessage("user", "你好")),
                ),
            ) { deltas += it }
            assertEquals("你好", result.text)
            assertEquals(listOf("你", "好"), deltas)
            assertEquals(3, result.usage.inputTokens)
            assertEquals(2, result.usage.outputTokens)
            assertEquals(2, attempts.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `streams openai tool calls and sends tool schema`() = runBlocking {
        val body = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { exchange ->
            body.set(exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8))
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val events = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"shell.exec","arguments":"{\"command\":\"ls\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent()
            exchange.responseBody.use { it.write(events.toByteArray()) }
        }
        server.start()
        try {
            val service = service("http://127.0.0.1:${server.address.port}/v1")
            val binding = service.resolve(binding(service), PROFILE_ID)
            val result = gateway(service).stream(
                binding = binding,
                request = ModelCallRequest(
                    model = "gpt-4o-mini",
                    messages = listOf(ModelGatewayMessage("user", "list files")),
                    tools = ToolCatalog.definitions(),
                ),
            ) { }
            assertEquals("shell.exec", result.toolCalls.single().name)
            assertEquals("{\"command\":\"ls\"}", result.toolCalls.single().arguments)
            assertTrue(body.get().contains("\"tools\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `401 maps to auth failed without retry`() = runBlocking {
        val attempts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { exchange ->
            attempts.incrementAndGet()
            exchange.sendResponseHeaders(401, 0)
            exchange.responseBody.close()
        }
        server.start()
        try {
            val service = service("http://127.0.0.1:${server.address.port}/v1")
            val binding = service.resolve(binding(service), PROFILE_ID)
            assertFailsWith<ModelException> {
                gateway(service).stream(
                    binding,
                    ModelCallRequest("gpt-4o-mini", listOf(ModelGatewayMessage("user", "hi"))),
                ) { }
            }
            assertEquals(1, attempts.get())
        } finally {
            server.stop(0)
        }
    }

    private fun gateway(service: ModelService): ModelGateway = ModelGateway(service)

    private fun service(baseUrl: String): ModelService {
        val store = FakeModelStore()
        val vault = SecretVault(KEY)
        val encrypted = vault.encrypt("sk-test")
        val credential = ProviderCredential(
            id = UUID.randomUUID().toString(),
            scope = CredentialScope.CLOUD_SERVER,
            provider = "openai",
            baseUrl = baseUrl,
            encryptedSecret = encrypted.ciphertext,
            secretNonce = encrypted.nonce,
            keyVersion = encrypted.keyVersion,
            status = CredentialStatus.ACTIVE,
            createdAt = "2026-11-24T00:00:00Z",
            revokedAt = null,
        )
        store.credentials[credential.id] = credential
        store.definitions[DEFINITION_ID] = ModelDefinition(
            id = DEFINITION_ID,
            provider = "openai",
            model = "gpt-4o-mini",
            displayName = "GPT-4o mini",
            capabilitiesJson = "[]",
            contextWindow = 32_768,
            inputPrice = 0.0,
            outputPrice = 0.0,
            createdAt = "2026-11-24T00:00:00Z",
        )
        val binding = ModelBinding(
            id = BINDING_ID,
            profileId = PROFILE_ID,
            modelDefinitionId = DEFINITION_ID,
            credentialId = credential.id,
            status = BindingStatus.ACTIVE.name,
            createdAt = "2026-11-24T00:00:00Z",
            updatedAt = "2026-11-24T00:00:00Z",
        )
        store.bindings[binding.id] = binding
        return ModelService(store, vault, FakeContainerService())
    }

    private fun binding(service: ModelService): String = BINDING_ID

    private companion object {
        val KEY = ByteArray(32) { it.toByte() }
        const val PROFILE_ID = "11111111-1111-1111-1111-111111111111"
        const val DEFINITION_ID = "22222222-2222-2222-2222-222222222222"
        const val BINDING_ID = "33333333-3333-3333-3333-333333333333"
    }
}
