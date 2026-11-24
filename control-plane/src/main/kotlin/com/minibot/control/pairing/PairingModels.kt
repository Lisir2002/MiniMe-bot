package com.minibot.control.pairing

import java.time.Instant

data class DeviceRecord(
    val id: String,
    val publicKey: String,
    val tokenHash: String,
    val deviceName: String,
    val createdAt: Instant,
)

data class PairingTokenRecord(
    val tokenHash: String,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val createdAt: Instant,
)

enum class ClaimStoreResult {
    OK,
    ALREADY_PAIRED,
    TOKEN_INVALID,
}

sealed interface ClaimResult {
    data class Success(
        val deviceId: String,
        val deviceToken: String,
        val serverId: String,
        val serverName: String,
    ) : ClaimResult

    data class Failure(
        val code: String,
        val message: String,
        val status: Int,
    ) : ClaimResult
}
