package com.minibot.control.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretVaultTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `encrypt and decrypt round trip`() {
        val vault = SecretVault(key)
        val encrypted = vault.encrypt("sk-test-secret")
        assertNotEquals("sk-test-secret", encrypted.ciphertext.decodeToString())
        assertEquals("sk-test-secret", vault.decrypt(encrypted))
    }

    @Test
    fun `each encryption uses a fresh nonce`() {
        val vault = SecretVault(key)
        val first = vault.encrypt("same-secret")
        val second = vault.encrypt("same-secret")
        assertTrue(first.nonce.contentEquals(second.nonce).not())
    }

    @Test
    fun `wrong key cannot decrypt`() {
        val encrypted = SecretVault(key).encrypt("secret")
        assertFailsWith<Exception> {
            SecretVault(ByteArray(32) { (it + 1).toByte() }).decrypt(encrypted)
        }
    }
}
