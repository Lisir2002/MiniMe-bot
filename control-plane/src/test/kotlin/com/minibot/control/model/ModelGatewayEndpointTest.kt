package com.minibot.control.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelGatewayEndpointTest {
    @Test
    fun `appends chat completions to versioned base urls`() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            buildChatCompletionsUrl("https://example.com"),
        )
        assertEquals(
            "https://example.com/v1/chat/completions",
            buildChatCompletionsUrl("https://example.com/v1/"),
        )
        assertEquals(
            "https://example.com/v2/chat/completions",
            buildChatCompletionsUrl("https://example.com/v2/"),
        )
    }

    @Test
    fun `keeps full endpoint unchanged`() {
        assertEquals(
            "https://example.com/v2/chat/completions",
            buildChatCompletionsUrl("https://example.com/v2/chat/completions"),
        )
    }
}
