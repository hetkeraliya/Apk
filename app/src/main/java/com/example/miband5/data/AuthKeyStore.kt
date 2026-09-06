package com.example.miband5.data

import android.content.Context

/**
 * Stores the 16-byte Mi Band auth key + last device locally.
 * NOTE: plain SharedPreferences for now — switch to
 * EncryptedSharedPreferences (androidx.security) before release.
 */
class AuthKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences("miband5_auth", Context.MODE_PRIVATE)

    var authKey: ByteArray?
        get() = prefs.getString(KEY_AUTH, null)?.hexToBytes()
        set(value) {
            prefs.edit().putString(KEY_AUTH, value?.toHex()).apply()
        }

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE, null)
        set(value) {
            prefs.edit().putString(KEY_DEVICE, value).apply()
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val KEY_AUTH = "auth_key_hex"
        private const val KEY_DEVICE = "last_device_address"
    }
}
