package com.minibot.control.tool

import com.minibot.control.model.ModelToolDefinition

object ToolCatalog {
    private val shellExecParameters = """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "Shell command to run inside the container workspace."
            },
            "cwd": {
              "type": "string",
              "description": "Optional working directory inside /workspace."
            },
            "timeoutMs": {
              "type": "integer",
              "description": "Optional timeout from 1 to 600 seconds.",
              "minimum": 1000,
              "maximum": 600000
            }
          },
          "required": ["command"],
          "additionalProperties": false
        }
    """.trimIndent()

    fun definitions(): List<ModelToolDefinition> {
        return listOf(
            ModelToolDefinition(
                name = "shell.exec",
                description = "Run a shell command inside the cloud container. " +
                    "Use /workspace for files and keep commands short-lived.",
                parametersJson = shellExecParameters,
            ),
        )
    }
}
