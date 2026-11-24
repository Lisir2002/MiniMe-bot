package com.minibot.control.pairing

import java.time.Instant

interface PairingStore {
    fun ensureServer(serverName: String, timezone: String): String

    fun seedPairingToken(tokenHash: String, expiresAt: Instant)

    fun activeDevice(): DeviceRecord?

    fun claimDevice(
        device: DeviceRecord,
        tokenHash: String,
        now: Instant,
    ): ClaimStoreResult

    fun findDeviceByTokenHash(tokenHash: String): DeviceRecord?
}
