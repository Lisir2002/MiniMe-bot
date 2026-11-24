package com.minibot.control.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object TokenHasher {
    private val secureRandom = SecureRandom()

    fun randomToken(byteCount: Int = 32): String {
        require(byteCount > 0)
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(leftBytes, rightBytes)
    }
}
