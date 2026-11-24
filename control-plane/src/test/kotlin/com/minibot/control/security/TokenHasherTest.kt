package com.minibot.control.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenHasherTest {
    @Test
    fun `sha256 is stable and hex encoded`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            TokenHasher.sha256Hex("hello"),
        )
    }

    @Test
    fun `random tokens are url safe and unique`() {
        val first = TokenHasher.randomToken()
        val second = TokenHasher.randomToken()

        assertTrue(first.isNotBlank())
        assertFalse(first.contains("+"))
        assertFalse(first.contains("/"))
        assertFalse(first.contains("="))
        assertFalse(first == second)
    }

    @Test
    fun `constant time equals works`() {
        assertTrue(TokenHasher.constantTimeEquals("abc", "abc"))
        assertFalse(TokenHasher.constantTimeEquals("abc", "abd"))
    }
}
