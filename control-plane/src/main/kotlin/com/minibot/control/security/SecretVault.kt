package com.minibot.control.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedSecret(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val keyVersion: Int = 1,
)

class SecretVault(private val masterKey: ByteArray) {
    private val random = SecureRandom()

    fun encrypt(plaintext: String): EncryptedSecret {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(masterKey, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        return EncryptedSecret(
            ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)),
            nonce = nonce,
        )
    }

    fun decrypt(encrypted: EncryptedSecret): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(masterKey, "AES"),
            GCMParameterSpec(TAG_BITS, encrypted.nonce),
        )
        return String(cipher.doFinal(encrypted.ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
