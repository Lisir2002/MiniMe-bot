package com.minibot.control.pairing

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FakePairingStore(
    private val expectedTokenHash: String?,
    private val tokenExpiresAt: Instant = Instant.parse("2030-01-01T00:00:00Z"),
) : PairingStore {
    var serverId: String = "server-1"
    var activeDevice: DeviceRecord? = null
    var tokenUsed: Boolean = false
    var seededTokenHash: String? = null

    override fun ensureServer(serverName: String, timezone: String): String = serverId

    override fun seedPairingToken(tokenHash: String, expiresAt: Instant) {
        seededTokenHash = tokenHash
    }

    override fun activeDevice(): DeviceRecord? = activeDevice

    override fun claimDevice(
        device: DeviceRecord,
        tokenHash: String,
        now: Instant,
    ): ClaimStoreResult {
        if (activeDevice != null) return ClaimStoreResult.ALREADY_PAIRED
        if (tokenUsed) return ClaimStoreResult.TOKEN_INVALID
        if (tokenHash != expectedTokenHash) return ClaimStoreResult.TOKEN_INVALID
        if (!tokenExpiresAt.isAfter(now)) return ClaimStoreResult.TOKEN_INVALID
        activeDevice = device
        tokenUsed = true
        return ClaimStoreResult.OK
    }

    override fun findDeviceByTokenHash(tokenHash: String): DeviceRecord? {
        return activeDevice?.takeIf { it.tokenHash == tokenHash }
    }
}

fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC)
