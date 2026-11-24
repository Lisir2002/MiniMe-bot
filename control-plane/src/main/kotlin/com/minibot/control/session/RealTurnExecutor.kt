package com.minibot.control.session

import com.minibot.control.model.ModelCallRequest
import com.minibot.control.model.ModelException
import com.minibot.control.model.ModelGateway
import com.minibot.control.model.ModelGatewayMessage
import com.minibot.control.model.ModelService
import com.minibot.control.model.ModelToolCall
import com.minibot.control.tool.ToolCallParser
import com.minibot.control.tool.ToolCatalog
import com.minibot.control.tool.ToolException
import com.minibot.control.tool.ToolExecutor
import com.minibot.control.tool.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Runs a cloud turn as a model/tool loop and persists every streamed assistant message.
 * Model streaming stays inside this class; container command execution is delegated to
 * [ToolExecutor] so approval and output handling are independent of the model transport.
 */
class RealTurnExecutor(
    private val store: SessionStore,
    private val events: EventStore,
    private val models: ModelService,
    private val gateway: ModelGateway,
    private val tools: ToolExecutor? = null,
) : TurnExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(turn: TurnRecord, session: SessionRecord) {
        val bindingId = session.modelBindingId
            ?: throw ModelException("binding_unavailable", "This session has no model binding", 409)
        val binding = models.resolve(bindingId, session.profileId)
        val conversation = buildHistory(session, binding.definition.contextWindow).toMutableList()

        var step = 0
        val checkpoint = store.findCheckpoint(turn.id)
        if (checkpoint != null) {
            val payload = runCatching { json.parseToJsonElement(checkpoint.payloadJson).jsonObject }.getOrNull()
            val approvalId = payload?.get("approvalId")?.jsonPrimitive?.contentOrNull
            val savedStep = payload?.get("step")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val call = payload?.get("call")?.jsonObject?.let { raw ->
                ModelToolCall(
                    id = raw["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = raw["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    arguments = raw["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                )
            }
            if (approvalId != null && call != null) {
                val result = executeToolAfterApproval(turn, session, call, approvalId)
                if (result.approvalPending) {
                    throw TurnSuspended(turn.id, result.approvalId ?: approvalId)
                }
                persistToolMessage(turn, session, call, result)
                conversation.clear()
                conversation.addAll(buildHistory(session, binding.definition.contextWindow))
                store.deleteCheckpoint(turn.id)
                step = savedStep
            }
        }

        while (step < MAX_MODEL_STEPS) {
            step++
            val messageId = UUID.randomUUID().toString()
            store.createMessage(
                sessionId = session.id,
                turnId = turn.id,
                role = "assistant",
                content = "",
                contentJson = buildJsonObject {
                    put("streamed", true)
                    put("model", binding.definition.model)
                    put("step", step)
                }.toString(),
                messageId = messageId,
            )
            val buffer = MessageStreamBuffer(
                events = events,
                sessionId = session.id,
                turnId = turn.id,
                messageId = messageId,
            )
            val result = try {
                gateway.stream(
                    binding = binding,
                    request = ModelCallRequest(
                        model = binding.definition.model,
                        messages = conversation,
                        tools = if (tools != null) ToolCatalog.definitions() else emptyList(),
                    ),
                ) { delta ->
                    buffer.append(delta)
                }
            } catch (error: Exception) {
                buffer.flush()
                if (buffer.totalText.isNotEmpty()) {
                    store.updateMessageContent(messageId, buffer.totalText.toString())
                }
                val modelError = error as? ModelException
                if (modelError?.code == "auth_failed") {
                    models.markCredentialInvalid(binding.credential.id)
                }
                models.recordCall(
                    sessionId = session.id,
                    turnId = turn.id,
                    bindingId = binding.binding.id,
                    provider = binding.definition.provider,
                    model = binding.definition.model,
                    usage = null,
                    status = "FAILED",
                    errorCode = modelError?.code ?: "provider_error",
                )
                throw error
            }

            buffer.flush()
            store.updateMessageContent(messageId, result.text)
            store.updateMessageContentJson(
                messageId,
                buildJsonObject {
                    put("streamed", true)
                    put("model", binding.definition.model)
                    put("step", step)
                    put("finishReason", result.finishReason ?: "")
                    put("toolCalls", toolCallsJson(result.toolCalls))
                }.toString(),
            )
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = messageId,
                type = "message.completed",
                payloadJson = buildJsonObject {
                    put("messageId", messageId)
                    put("role", "assistant")
                    put("content", result.text)
                    put("finishReason", result.finishReason ?: "")
                    put("toolCalls", toolCallsJson(result.toolCalls))
                }.toString(),
            )
            models.recordCall(
                sessionId = session.id,
                turnId = turn.id,
                bindingId = binding.binding.id,
                provider = binding.definition.provider,
                model = binding.definition.model,
                usage = result.usage,
                status = "COMPLETED",
            )

            conversation += ModelGatewayMessage(
                role = "assistant",
                content = result.text,
                toolCalls = result.toolCalls,
            )
            if (result.toolCalls.isEmpty()) {
                return
            }
            if (tools == null) {
                throw ModelException("tool_runtime_unavailable", "Tool runtime is not configured", 503)
            }

            for (call in result.toolCalls) {
                val toolResult = executeTool(turn, session, call)
                if (toolResult.approvalPending) {
                    val approvalId = toolResult.approvalId
                        ?: throw ModelException("approval_state_lost", "Approval id is missing", 500)
                    store.saveCheckpoint(
                        turn.id,
                        session.id,
                        buildJsonObject {
                            put("approvalId", approvalId)
                            put("step", step)
                            put("call", buildJsonObject {
                                put("id", call.id)
                                put("name", call.name)
                                put("arguments", call.arguments)
                            })
                        }.toString(),
                    )
                    throw TurnSuspended(turn.id, approvalId)
                }
                val toolContent = persistToolMessage(turn, session, call, toolResult)
                conversation += ModelGatewayMessage(
                    role = "tool",
                    content = toolContent,
                    toolCallId = call.id,
                    name = call.name,
                )
            }
        }

        throw ModelException(
            "tool_loop_limit",
            "The model exceeded the maximum number of tool steps",
            409,
        )
    }

    private suspend fun executeTool(
        turn: TurnRecord,
        session: SessionRecord,
        call: ModelToolCall,
    ): ToolResult {
        val executor = tools ?: throw ModelException(
            "tool_runtime_unavailable",
            "Tool runtime is not configured",
            503,
        )
        return try {
            val request = ToolCallParser.parse(call, turn, session)
            executor.execute(turn, session, request)
        } catch (error: ToolException) {
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "tool.failed",
                payloadJson = buildJsonObject {
                    put("callId", call.id)
                    put("tool", call.name)
                    put("errorCode", error.code)
                    put("message", error.message ?: "Tool request rejected")
                }.toString(),
            )
            ToolResult(
                callId = call.id,
                exitCode = null,
                durationMs = 0,
                stdoutPreview = "",
                stderrPreview = "",
                truncated = false,
                outputRef = null,
                errorCode = error.code,
            )
        }
    }

    private suspend fun executeToolAfterApproval(
        turn: TurnRecord,
        session: SessionRecord,
        call: ModelToolCall,
        approvalId: String,
    ): ToolResult {
        val executor = tools ?: throw ModelException(
            "tool_runtime_unavailable",
            "Tool runtime is not configured",
            503,
        )
        return try {
            val request = ToolCallParser.parse(call, turn, session)
            executor.executeAfterApproval(turn, session, request, approvalId)
        } catch (error: ToolException) {
            events.append(
                sessionId = session.id,
                turnId = turn.id,
                messageId = null,
                type = "tool.failed",
                payloadJson = buildJsonObject {
                    put("callId", call.id)
                    put("tool", call.name)
                    put("errorCode", error.code)
                    put("message", error.message ?: "Tool request rejected")
                }.toString(),
            )
            ToolResult(
                callId = call.id,
                exitCode = null,
                durationMs = 0,
                stdoutPreview = "",
                stderrPreview = "",
                truncated = false,
                outputRef = null,
                errorCode = error.code,
            )
        }
    }

    private suspend fun persistToolMessage(
        turn: TurnRecord,
        session: SessionRecord,
        call: ModelToolCall,
        toolResult: ToolResult,
    ): String {
        val toolContent = toolResultContent(toolResult)
        val toolMessageId = UUID.randomUUID().toString()
        store.createMessage(
            sessionId = session.id,
            turnId = turn.id,
            role = "tool",
            content = toolContent,
            contentJson = buildJsonObject {
                put("toolCallId", call.id)
                put("name", call.name)
                put("exitCode", toolResult.exitCode ?: -1)
                put("timedOut", toolResult.timedOut)
                put("denied", toolResult.denied)
                put("errorCode", toolResult.errorCode ?: "")
                put("outputRef", toolResult.outputRef ?: "")
            }.toString(),
            messageId = toolMessageId,
        )
        events.append(
            sessionId = session.id,
            turnId = turn.id,
            messageId = toolMessageId,
            type = "message.completed",
            payloadJson = buildJsonObject {
                put("messageId", toolMessageId)
                put("role", "tool")
                put("toolCallId", call.id)
                put("name", call.name)
                put("content", toolContent)
            }.toString(),
        )
        return toolContent
    }

    private fun toolResultContent(result: ToolResult): String {
        return buildJsonObject {
            put("callId", result.callId)
            put("exitCode", result.exitCode ?: -1)
            put("durationMs", result.durationMs)
            put("timedOut", result.timedOut)
            put("denied", result.denied)
            put("errorCode", result.errorCode ?: "")
            put("outputRef", result.outputRef ?: "")
            put("stdout", result.stdoutPreview.take(24_576))
            put("stderr", result.stderrPreview.take(8_192))
            put("truncated", result.truncated)
        }.toString()
    }

    private fun toolCallsJson(calls: List<ModelToolCall>) = buildJsonArray {
        calls.forEach { call ->
            add(
                buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    put("arguments", call.arguments)
                },
            )
        }
    }

    private fun buildHistory(session: SessionRecord, contextWindow: Int): List<ModelGatewayMessage> {
        val budget = (contextWindow * 3).coerceIn(4_000, 240_000)
        val candidates = store.latestMessages(session.id, 200)
            .filter { it.role in setOf("system", "user", "assistant", "tool") }
            .filter { it.content.isNotBlank() || it.hasToolCalls() || it.role == "tool" }
            .map { it.toGatewayMessage() }
        val selected = mutableListOf<ModelGatewayMessage>()
        var used = 0
        for (message in candidates.asReversed()) {
            val size = message.content.length + message.toolCalls.sumOf { it.arguments.length } + 16
            if (selected.isNotEmpty() && used + size > budget) break
            selected += message
            used += size
        }
        return selected.asReversed()
    }

    private fun MessageRecord.toGatewayMessage(): ModelGatewayMessage {
        val meta = runCatching { json.parseToJsonElement(contentJson).jsonObject }.getOrNull()
        val calls = meta?.get("toolCalls")?.jsonArray?.map { raw ->
            val item = raw.jsonObject
            ModelToolCall(
                id = item["id"]?.jsonPrimitive?.contentOrNull ?: "",
                name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                arguments = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
            )
        }.orEmpty()
        val toolCallId = meta?.get("toolCallId")?.jsonPrimitive?.contentOrNull
        val toolName = meta?.get("name")?.jsonPrimitive?.contentOrNull
        return ModelGatewayMessage(
            role = role,
            content = content,
            toolCallId = toolCallId,
            name = toolName,
            toolCalls = calls,
        )
    }

    private fun MessageRecord.hasToolCalls(): Boolean {
        val meta = runCatching { json.parseToJsonElement(contentJson).jsonObject }.getOrNull()
        return meta?.get("toolCalls")?.jsonArray?.isNotEmpty() == true
    }

    private companion object {
        const val MAX_MODEL_STEPS = 8
    }
}

private class MessageStreamBuffer(
    private val events: EventStore,
    private val sessionId: String,
    private val turnId: String,
    private val messageId: String,
) {
    val totalText = StringBuilder()
    private val pending = StringBuilder()
    private var lastFlushAt = System.currentTimeMillis()

    suspend fun append(delta: String) {
        totalText.append(delta)
        pending.append(delta)
        val now = System.currentTimeMillis()
        if (pending.length >= 200 || now - lastFlushAt >= 50) {
            flush()
        }
    }

    suspend fun flush() {
        if (pending.isEmpty()) {
            lastFlushAt = System.currentTimeMillis()
            return
        }
        val delta = pending.toString()
        pending.clear()
        events.append(
            sessionId = sessionId,
            turnId = turnId,
            messageId = messageId,
            type = "message.delta",
            payloadJson = buildJsonObject {
                put("messageId", messageId)
                put("delta", delta)
            }.toString(),
        )
        lastFlushAt = System.currentTimeMillis()
    }
}
