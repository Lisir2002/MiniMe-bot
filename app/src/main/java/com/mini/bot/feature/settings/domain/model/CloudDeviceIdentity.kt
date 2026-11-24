package com.mini.bot.feature.settings.domain.model

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

data class CloudDeviceKey(
    val publicKeyBase64: String,
    val privateKeyBase64: String,
)

/**
 * 首次配对时生成的设备身份。控制面只保存公钥；私钥留在 App，供后续挑战签名使用。
 *
 * 当前配对接口不校验签名，但先用真实 EC P-256 密钥，避免以后换身份协议时推翻已配对设备。
 */
object CloudDeviceIdentity {
    fun generate(): CloudDeviceKey {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val pair = generator.generateKeyPair()
        return CloudDeviceKey(
            publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded),
            privateKeyBase64 = Base64.getEncoder().encodeToString(pair.private.encoded),
        )
    }
}
