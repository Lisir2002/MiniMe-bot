package com.minibot.control.pairing

import com.minibot.control.security.TokenHasher
import java.time.Clock
import java.time.Instant
import java.util.UUID

class PairingService(
    private val store: PairingStore,
    private val serverId: String,
    private val serverName: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun claim(
        bootstrapToken: String,
        devicePublicKey: String,
        deviceName: String,
    ): ClaimResult {
        val token = bootstrapToken.trim()
        val publicKey = devicePublicKey.trim()
        val name = deviceName.trim().ifBlank { "Android" }

        if (token.isBlank() || publicKey.isBlank()) {
            return ClaimResult.Failure(
                code = "invalid_request",
                message = "bootstrapToken and devicePublicKey are required",
                status = 400,
            )
        }
        if (publicKey.length > 8192) {
            return ClaimResult.Failure(
                code = "invalid_request",
                message = "devicePublicKey is too large",
                status = 400,
            )
        }
        if (store.activeDevice() != null) {
            return ClaimResult.Failure(
                code = "device_already_paired",
                message = "This server already has a paired device",
                status = 409,
            )
        }

        val now = Instant.now(clock)
        val tokenHash = TokenHasher.sha256Hex(token)
        val deviceId = UUID.randomUUID().toString()
        val deviceToken = TokenHasher.randomToken()
        val device = DeviceRecord(
            id = deviceId,
            publicKey = publicKey,
            tokenHash = TokenHasher.sha256Hex(deviceToken),
            deviceName = name,
            createdAt = now,
        )

        return when (store.claimDevice(device, tokenHash, now)) {
            ClaimStoreResult.OK -> ClaimResult.Success(
                deviceId = deviceId,
                deviceToken = deviceToken,
                serverId = serverId,
                serverName = serverName,
            )

            ClaimStoreResult.ALREADY_PAIRED -> ClaimResult.Failure(
                code = "device_already_paired",
                message = "This server already has a paired device",
                status = 409,
            )

            ClaimStoreResult.TOKEN_INVALID -> ClaimResult.Failure(
                code = "invalid_bootstrap_token",
                message = "Bootstrap token is invalid, expired or already used",
                status = 401,
            )
        }
    }

    fun authenticate(rawDeviceToken: String): DeviceRecord? {
        if (rawDeviceToken.isBlank()) return null
        return store.findDeviceByTokenHash(TokenHasher.sha256Hex(rawDeviceToken))
    }
}
