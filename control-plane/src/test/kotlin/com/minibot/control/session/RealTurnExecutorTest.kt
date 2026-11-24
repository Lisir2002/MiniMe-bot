package com.minibot.control.session

import com.minibot.control.model.BindingStatus
import com.minibot.control.model.CredentialScope
import com.minibot.control.model.CredentialStatus
import com.minibot.control.model.FakeModelStore
import com.minibot.control.model.ModelBinding
import com.minibot.control.model.ModelDefinition
import com.minibot.control.model.ModelGateway
import com.minibot.control.model.ModelService
import com.minibot.control.model.ProviderCredential
import com.minibot.control.security.SecretVault
import com.minibot.control.tool.ToolExecutor
import com.minibot.control.tool.ToolRequest
import com.minibot.control.tool.ToolResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealTurnExecutorTest {
    @Test
    fun `assistant stream is persisted and completed`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use {
                it.write(
                    """
                    data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent().toByteArray(),
                )
            }
        }
        server.start()
        try {
            val store = FakeSessionStore()
            val modelStore = FakeModelStore()
            val vault = SecretVault(KEY)
            val encrypted = vault.encrypt("sk-test")
            val credentialId = UUID.randomUUID().toString()
            modelStore.credentials[credentialId] = ProviderCredential(
                id = credentialId,
                scope = CredentialScope.CLOUD_SERVER,
                provider = "openai",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                encryptedSecret = encrypted.ciphertext,
                secretNonce = encrypted.nonce,
                keyVersion = encrypted.keyVersion,
                status = CredentialStatus.ACTIVE,
                createdAt = "2026-11-24T00:00:00Z",
                revokedAt = null,
            )
            val definitionId = UUID.randomUUID().toString()
            modelStore.definitions[definitionId] = ModelDefinition(
                id = definitionId,
                provider = "openai",
                model = "gpt-4o-mini",
                displayName = "GPT-4o mini",
                capabilitiesJson = "[]",
                contextWindow = 32_768,
                inputPrice = 0.0,
                outputPrice = 0.0,
                createdAt = "2026-11-24T00:00:00Z",
            )
            val bindingId = UUID.randomUUID().toString()
            modelStore.bindings[bindingId] = ModelBinding(
                id = bindingId,
                profileId = PROFILE_ID,
                modelDefinitionId = definitionId,
                credentialId = credentialId,
                status = BindingStatus.ACTIVE.name,
                createdAt = "2026-11-24T00:00:00Z",
                updatedAt = "2026-11-24T00:00:00Z",
            )
            val models = ModelService(modelStore, vault, FakeContainerService())
            val session = store.createSession(PROFILE_ID, "workspace", "测试会话", bindingId)
            val turn = store.createTurn(
                session.id,
                "client-message-1",
                "你好",
                """{"role":"user"}""",
            ).turn
            RealTurnExecutor(store, EventStore(store, EventHub()), models, ModelGateway(models))
                .execute(turn, session)

            val assistant = store.latestMessages(session.id, 20).last { it.role == "assistant" }
            assertEquals("你好", assistant.content)
            val events = store.listEvents(session.id, 0, 50)
            assertTrue(events.any { it.type == "message.delta" })
            assertTrue(events.any { it.type == "message.completed" })
            assertEquals("COMPLETED", modelStore.calls.last().status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `tool call loops until the model returns final text`() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val body = if (calls.incrementAndGet() == 1) {
                """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"shell.exec","arguments":"{\"command\":\"ls\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """
            } else {
                """
                data: {"choices":[{"delta":{"content":"完成了"},"finish_reason":"stop"}]}

                data: [DONE]

                """
            }
            exchange.responseBody.use { it.write(body.trimIndent().toByteArray()) }
        }
        server.start()
        try {
            val store = FakeSessionStore()
            val modelStore = FakeModelStore()
            val vault = SecretVault(KEY)
            val encrypted = vault.encrypt("sk-test")
            val credentialId = UUID.randomUUID().toString()
            modelStore.credentials[credentialId] = ProviderCredential(
                id = credentialId,
                scope = CredentialScope.CLOUD_SERVER,
                provider = "openai",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                encryptedSecret = encrypted.ciphertext,
                secretNonce = encrypted.nonce,
                keyVersion = encrypted.keyVersion,
                status = CredentialStatus.ACTIVE,
                createdAt = "2026-11-24T00:00:00Z",
                revokedAt = null,
            )
            val definitionId = UUID.randomUUID().toString()
            modelStore.definitions[definitionId] = ModelDefinition(
                id = definitionId,
                provider = "openai",
                model = "gpt-4o-mini",
                displayName = "GPT-4o mini",
                capabilitiesJson = "[]",
                contextWindow = 32_768,
                inputPrice = 0.0,
                outputPrice = 0.0,
                createdAt = "2026-11-24T00:00:00Z",
            )
            val bindingId = UUID.randomUUID().toString()
            modelStore.bindings[bindingId] = ModelBinding(
                id = bindingId,
                profileId = PROFILE_ID,
                modelDefinitionId = definitionId,
                credentialId = credentialId,
                status = BindingStatus.ACTIVE.name,
                createdAt = "2026-11-24T00:00:00Z",
                updatedAt = "2026-11-24T00:00:00Z",
            )
            val models = ModelService(modelStore, vault, FakeContainerService())
            val session = store.createSession(PROFILE_ID, "workspace", "tool loop", bindingId)
            val turn = store.createTurn(session.id, "client-tool", "list", "{}").turn
            val toolExecutor = object : ToolExecutor {
                override suspend fun execute(turn: TurnRecord, session: SessionRecord, request: ToolRequest): ToolResult {
                    return result(request)
                }

                override suspend fun executeAfterApproval(
                    turn: TurnRecord,
                    session: SessionRecord,
                    request: ToolRequest,
                    approvalId: String,
                ): ToolResult = result(request)

                private fun result(request: ToolRequest) = ToolResult(
                    callId = request.callId,
                    exitCode = 0,
                    durationMs = 5,
                    stdoutPreview = "file.txt\n",
                    stderrPreview = "",
                    truncated = false,
                    outputRef = "/tmp/tool.log",
                )
            }
            RealTurnExecutor(store, EventStore(store, EventHub()), models, ModelGateway(models), toolExecutor)
                .execute(turn, session)

            val messages = store.latestMessages(session.id, 20)
            assertTrue(messages.any { it.role == "tool" && it.content.contains("file.txt") })
            assertEquals("完成了", messages.last { it.role == "assistant" }.content)
            assertEquals(2, modelStore.calls.size)
            assertEquals(2, calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `suspended turn resumes from checkpoint after approval`() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val body = if (calls.incrementAndGet() == 1) {
                """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"shell.exec","arguments":"{\"command\":\"ls\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """
            } else {
                """
                data: {"choices":[{"delta":{"content":"完成了"},"finish_reason":"stop"}]}

                data: [DONE]

                """
            }
            exchange.responseBody.use { it.write(body.trimIndent().toByteArray()) }
        }
        server.start()
        try {
            val store = FakeSessionStore()
            val modelStore = FakeModelStore()
            val vault = SecretVault(KEY)
            val encrypted = vault.encrypt("sk-test")
            val credentialId = UUID.randomUUID().toString()
            modelStore.credentials[credentialId] = ProviderCredential(
                id = credentialId,
                scope = CredentialScope.CLOUD_SERVER,
                provider = "openai",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                encryptedSecret = encrypted.ciphertext,
                secretNonce = encrypted.nonce,
                keyVersion = encrypted.keyVersion,
                status = CredentialStatus.ACTIVE,
                createdAt = "2026-11-24T00:00:00Z",
                revokedAt = null,
            )
            val definitionId = UUID.randomUUID().toString()
            modelStore.definitions[definitionId] = ModelDefinition(
                id = definitionId,
                provider = "openai",
                model = "gpt-4o-mini",
                displayName = "GPT-4o mini",
                capabilitiesJson = "[]",
                contextWindow = 32_768,
                inputPrice = 0.0,
                outputPrice = 0.0,
                createdAt = "2026-11-24T00:00:00Z",
            )
            val bindingId = UUID.randomUUID().toString()
            modelStore.bindings[bindingId] = ModelBinding(
                id = bindingId,
                profileId = PROFILE_ID,
                modelDefinitionId = definitionId,
                credentialId = credentialId,
                status = BindingStatus.ACTIVE.name,
                createdAt = "2026-11-24T00:00:00Z",
                updatedAt = "2026-11-24T00:00:00Z",
            )
            val models = ModelService(modelStore, vault, FakeContainerService())
            val session = store.createSession(PROFILE_ID, "workspace", "resume", bindingId)
            val turn = store.createTurn(session.id, "client-resume", "list", "{}").turn
            val toolExecutor = object : ToolExecutor {
                override suspend fun execute(turn: TurnRecord, session: SessionRecord, request: ToolRequest): ToolResult {
                    return result(request, pending = true)
                }

                override suspend fun executeAfterApproval(
                    turn: TurnRecord,
                    session: SessionRecord,
                    request: ToolRequest,
                    approvalId: String,
                ): ToolResult = result(request, pending = false)

                private fun result(request: ToolRequest, pending: Boolean) = ToolResult(
                    callId = request.callId,
                    exitCode = if (pending) null else 0,
                    durationMs = 5,
                    stdoutPreview = if (pending) "" else "file.txt\n",
                    stderrPreview = "",
                    truncated = false,
                    outputRef = if (pending) null else "/tmp/tool.log",
                    approvalPending = pending,
                    approvalId = if (pending) "approval-1" else null,
                    errorCode = if (pending) "approval_pending" else null,
                )
            }
            val executor = RealTurnExecutor(
                store,
                EventStore(store, EventHub()),
                models,
                ModelGateway(models),
                toolExecutor,
            )
            assertFailsWith<TurnSuspended> { executor.execute(turn, session) }
            assertEquals("approval-1", store.findCheckpoint(turn.id)?.payloadJson?.let {
                Regex("\"approvalId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1)
            })
            assertEquals(1, calls.get())

            executor.execute(turn, session)
            assertNull(store.findCheckpoint(turn.id))
            val messages = store.latestMessages(session.id, 20)
            assertTrue(messages.any { it.role == "tool" && it.content.contains("file.txt") })
            assertEquals("完成了", messages.last { it.role == "assistant" }.content)
            assertEquals(2, calls.get())
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        val KEY = ByteArray(32) { it.toByte() }
        const val PROFILE_ID = "44444444-4444-4444-4444-444444444444"
    }
}
