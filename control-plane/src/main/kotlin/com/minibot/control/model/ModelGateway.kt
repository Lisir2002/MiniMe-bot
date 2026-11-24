package com.minibot.control.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ModelGateway(
    private val models: ModelService,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_2)
        .build()

    suspend fun stream(
        binding: ResolvedModelBinding,
        request: ModelCallRequest,
        onDelta: suspend (String) -> Unit,
    ): ModelCallResult {
        if (binding.credential.provider !in OPENAI_COMPATIBLE_PROVIDERS) {
            throw ModelException(
                "model_not_supported",
                "Provider ${binding.credential.provider} is not supported by this runtime",
                409,
            )
        }
        val secret = models.decryptCredential(binding.credential)
        var receivedAny = false
        var attempt = 0
        while (true) {
            try {
                return streamOnce(
                    binding = binding,
                    secret = secret,
                    request = request,
                    onDelta = { delta ->
                        receivedAny = true
                        onDelta(delta)
                    },
                )
            } catch (error: ModelException) {
                val retryable = error.status == 429 || error.status in 500..599
                if (!retryable || receivedAny || attempt >= MAX_RETRIES) throw error
                attempt++
                delay(RETRY_BASE_MS * (1L shl (attempt - 1)))
            } catch (error: java.io.IOException) {
                if (receivedAny || attempt >= MAX_RETRIES) {
                    throw ModelException("network_error", "Model connection failed", 502)
                }
                attempt++
                delay(RETRY_BASE_MS * (1L shl (attempt - 1)))
            }
        }
    }

    private suspend fun streamOnce(
        binding: ResolvedModelBinding,
        secret: String,
        request: ModelCallRequest,
        onDelta: suspend (String) -> Unit,
    ): ModelCallResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("max_tokens", request.maxTokens)
            put("messages", buildJsonArray { request.messages.forEach { add(messageJson(it)) } })
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray { request.tools.forEach { add(toolJson(it)) } })
                put("tool_choice", "auto")
            }
        }.toString()

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(endpoint(binding.credential.baseUrl)))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $secret")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            val status = response.statusCode()
            val errorBody = response.body().use { input ->
                String(input.readNBytes(4_096), Charsets.UTF_8)
            }
            val contextTooLong = status == 413 ||
                errorBody.contains("context_length_exceeded", ignoreCase = true) ||
                errorBody.contains("maximum context length", ignoreCase = true) ||
                errorBody.contains("context too long", ignoreCase = true)
            throw when {
                contextTooLong -> ModelException("context_too_long", "The request exceeds the model context", 413)
                status == 401 || status == 403 -> ModelException("auth_failed", "Model credential was rejected", 401)
                status == 429 -> ModelException("rate_limited", "Model provider rate limited the request", 429)
                status == 400 -> ModelException("provider_error", "Model provider rejected the request", 400)
                else -> ModelException("provider_error", "Model provider returned HTTP $status", status)
            }
        }

        val text = StringBuilder()
        val toolBuffers = mutableListOf<ToolCallBuilder>()
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var finishReason: String? = null
        response.body().bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val chunk = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
                    ?: continue
                val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                val delta = choice?.get("delta") as? JsonObject
                val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (!content.isNullOrEmpty()) {
                    text.append(content)
                    onDelta(content)
                }
                (delta?.get("tool_calls") as? JsonArray)?.forEach { raw ->
                    val item = raw.jsonObject
                    val index = item["index"]?.jsonPrimitive?.intOrNull ?: 0
                    while (toolBuffers.size <= index) toolBuffers += ToolCallBuilder()
                    val buffer = toolBuffers[index]
                    buffer.id.append(item["id"]?.jsonPrimitive?.contentOrNull ?: "")
                    val function = item["function"]?.jsonObject
                    buffer.name.append(function?.get("name")?.jsonPrimitive?.contentOrNull ?: "")
                    buffer.arguments.append(function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "")
                }
                val reason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                if (!reason.isNullOrBlank()) finishReason = reason
                (chunk["usage"] as? JsonObject)?.let { usage ->
                    inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    outputTokens = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                }
            }
        }
        val toolCalls = toolBuffers
            .filter { it.name.isNotBlank() }
            .mapIndexed { index, buffer ->
                ModelToolCall(
                    id = buffer.id.toString().ifBlank { "call_$index" },
                    name = buffer.name.toString(),
                    arguments = buffer.arguments.toString().ifBlank { "{}" },
                )
            }
        if (text.isEmpty() && toolCalls.isEmpty()) {
            throw ModelException("provider_error", "Model returned an empty response", 502)
        }
        ModelCallResult(
            text = text.toString(),
            usage = ModelCallUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                latencyMs = System.currentTimeMillis() - startedAt,
            ),
            finishReason = finishReason,
            toolCalls = toolCalls,
        )
    }

    private fun messageJson(message: ModelGatewayMessage): JsonObject {
        return buildJsonObject {
            put("role", message.role)
            put("content", message.content)
            message.toolCallId?.let { put("tool_call_id", it) }
            message.name?.let { put("name", it) }
            if (message.toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    buildJsonArray {
                        message.toolCalls.forEach { call ->
                            add(
                                buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", call.name)
                                            put("arguments", call.arguments)
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    private fun toolJson(tool: ModelToolDefinition): JsonObject {
        return buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put(
                        "parameters",
                        runCatching { json.parseToJsonElement(tool.parametersJson) }
                            .getOrElse { buildJsonObject { } },
                    )
                },
            )
        }
    }

    private fun endpoint(baseUrl: String): String = buildChatCompletionsUrl(baseUrl)

    private data class ToolCallBuilder(
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_BASE_MS = 1_000L
        val OPENAI_COMPATIBLE_PROVIDERS = setOf(
            "openai",
            "openai_compatible",
            "custom",
            "deepseek",
            "moonshot",
        )
    }
}

internal fun buildChatCompletionsUrl(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    return when {
        normalized.endsWith("/chat/completions") -> normalized
        Regex("/v\\d+$").containsMatchIn(normalized) -> "$normalized/chat/completions"
        else -> "$normalized/v1/chat/completions"
    }
}
