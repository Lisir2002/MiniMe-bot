package com.minibot.control.tool

import com.minibot.control.model.ModelToolCall
import com.minibot.control.session.SessionRecord
import com.minibot.control.session.TurnRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ToolCallParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(call: ModelToolCall, turn: TurnRecord, session: SessionRecord): ToolRequest {
        if (call.name != "shell.exec") {
            throw ToolException("unsupported_tool", "Unsupported tool: ${call.name}")
        }
        val arguments = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrNull()
            ?: throw ToolException("invalid_arguments", "Tool arguments must be a JSON object")
        val command = arguments["command"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw ToolException("invalid_command", "command is required")
        if (command.isBlank()) throw ToolException("invalid_command", "command is required")
        val cwd = arguments["cwd"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val timeoutMs = arguments["timeoutMs"]?.jsonPrimitive?.intOrNull?.toLong() ?: 120_000L
        return ToolRequest(
            callId = call.id,
            turnId = turn.id,
            sessionId = session.id,
            tool = call.name,
            command = command,
            cwd = cwd,
            timeoutMs = timeoutMs,
        )
    }
}
