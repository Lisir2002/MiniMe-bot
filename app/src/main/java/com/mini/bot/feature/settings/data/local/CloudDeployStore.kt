package com.mini.bot.feature.settings.data.local

import android.content.Context
import com.mini.bot.core.security.KeystoreCipher

/**
 * 云端部署向导的本地状态。
 *
 * 地址可明文保存；设备令牌和配对私钥属于长期凭据，写入前用 Android Keystore 加密。
 * 读取失败时返回 null，让向导退回重新配对，而不是拿空令牌去请求服务器。
 */
class CloudDeployStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadEndpoint(): String = prefs.getString(KEY_ENDPOINT, null).orEmpty()

    fun saveEndpoint(baseUrl: String) {
        prefs.edit().putString(KEY_ENDPOINT, baseUrl).apply()
    }

    fun loadDeviceToken(): String? =
        prefs.getString(KEY_DEVICE_TOKEN, null)
            ?.let { runCatching { KeystoreCipher.decryptString(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    fun saveDeviceToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, KeystoreCipher.encryptString(token)).apply()
    }

    fun loadDevicePublicKey(): String? =
        prefs.getString(KEY_DEVICE_PUBLIC_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveDevicePublicKey(publicKey: String) {
        prefs.edit().putString(KEY_DEVICE_PUBLIC_KEY, publicKey).apply()
    }

    fun loadDevicePrivateKey(): String? =
        prefs.getString(KEY_DEVICE_PRIVATE_KEY, null)
            ?.let { runCatching { KeystoreCipher.decryptString(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    fun saveDevicePrivateKey(privateKey: String) {
        prefs.edit().putString(KEY_DEVICE_PRIVATE_KEY, KeystoreCipher.encryptString(privateKey)).apply()
    }

    fun loadServerName(): String? =
        prefs.getString(KEY_SERVER_NAME, null)?.takeIf { it.isNotBlank() }

    fun saveServerName(serverName: String) {
        prefs.edit().putString(KEY_SERVER_NAME, serverName).apply()
    }

    fun savePairing(
        deviceToken: String,
        publicKey: String,
        privateKey: String,
        serverName: String,
    ) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, KeystoreCipher.encryptString(deviceToken))
            .putString(KEY_DEVICE_PUBLIC_KEY, publicKey)
            .putString(KEY_DEVICE_PRIVATE_KEY, KeystoreCipher.encryptString(privateKey))
            .putString(KEY_SERVER_NAME, serverName)
            .apply()
    }

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_DEVICE_PUBLIC_KEY)
            .remove(KEY_DEVICE_PRIVATE_KEY)
            .remove(KEY_SERVER_NAME)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "cloud_deploy"
        const val KEY_ENDPOINT = "control_plane_url"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_DEVICE_PUBLIC_KEY = "device_public_key"
        const val KEY_DEVICE_PRIVATE_KEY = "device_private_key"
        const val KEY_SERVER_NAME = "server_name"
    }
}
