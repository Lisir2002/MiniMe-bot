package com.minibot.control.pairing

import com.minibot.control.security.TokenHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingServiceTest {
    private val bootstrapToken = "bootstrap-token-for-test"
    private val tokenHash = TokenHasher.sha256Hex(bootstrapToken)

    @Test
    fun `claim succeeds once and returns a device token`() {
        val store = FakePairingStore(expectedTokenHash = tokenHash)
        val service = PairingService(store, "server-1", "Mini-AIBOT Cloud", fixedClock())

        val result = service.claim(
            bootstrapToken = bootstrapToken,
            devicePublicKey = "test-public-key",
            deviceName = "Pixel",
        )

        assertTrue(result is ClaimResult.Success)
        result as ClaimResult.Success
        assertEquals("server-1", result.serverId)
        assertEquals("Mini-AIBOT Cloud", result.serverName)
        assertTrue(result.deviceToken.isNotBlank())
        assertTrue(store.activeDevice != null)
        assertTrue(store.tokenUsed)
        assertEquals(
            TokenHasher.sha256Hex(result.deviceToken),
            store.activeDevice?.tokenHash,
        )
        assertNull(service.authenticate("wrong-token"))
        assertNotNull(service.authenticate(result.deviceToken))
    }

    @Test
    fun `claim is rejected when a device is already paired`() {
        val store = FakePairingStore(expectedTokenHash = tokenHash)
        store.activeDevice = DeviceRecord(
            id = "device-1",
            publicKey = "pk",
            tokenHash = "hash",
            deviceName = "Old",
            createdAt = fixedClock().instant(),
        )
        val service = PairingService(store, "server-1", "Mini-AIBOT Cloud", fixedClock())

        val result = service.claim(bootstrapToken, "pk-2", "New")

        assertEquals("device_already_paired", (result as ClaimResult.Failure).code)
        assertEquals(409, result.status)
    }

    @Test
    fun `claim is rejected for an invalid bootstrap token`() {
        val store = FakePairingStore(expectedTokenHash = tokenHash)
        val service = PairingService(store, "server-1", "Mini-AIBOT Cloud", fixedClock())

        val result = service.claim("wrong-token", "pk", "Android")

        assertEquals("invalid_bootstrap_token", (result as ClaimResult.Failure).code)
        assertEquals(401, result.status)
    }

    @Test
    fun `claim is rejected for expired bootstrap token`() {
        val store = FakePairingStore(
            expectedTokenHash = tokenHash,
            tokenExpiresAt = fixedClock().instant().minusSeconds(1),
        )
        val service = PairingService(store, "server-1", "Mini-AIBOT Cloud", fixedClock())

        val result = service.claim(bootstrapToken, "pk", "Android")

        assertEquals("invalid_bootstrap_token", (result as ClaimResult.Failure).code)
        assertEquals(401, result.status)
    }

    @Test
    fun `claim rejects blank request fields`() {
        val store = FakePairingStore(expectedTokenHash = tokenHash)
        val service = PairingService(store, "server-1", "Mini-AIBOT Cloud", fixedClock())

        val result = service.claim("", "", "")

        assertEquals("invalid_request", (result as ClaimResult.Failure).code)
        assertEquals(400, result.status)
    }
}
