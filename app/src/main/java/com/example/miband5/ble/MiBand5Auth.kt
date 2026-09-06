package com.example.miband5.ble

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Mi Band 5 auth challenge-response handshake.
 *
 * Sequence verified against community RE:
 *  - https://leojrfs.github.io/writing/miband2-part1-auth/ (Mi Band 2)
 *  - https://medium.com/@yogeshojha/i-hacked-xiaomi-miband-3-and-here-is-how-i-did-it-43d68c272391 (Mi Band 3)
 *  - https://blog.shravanrevanna.me/i-reverse-engineered-my-2k-mi-band-to-read-my-heart-rate-live-on-my-mac-and-sync-with-smart-lights (Mi Band 5)
 *
 *   1. Send the 16-byte auth key:        { 0x01, 0x08, authKey[16] }
 *        -> expect { 0x10, 0x01, 0x01 } (success) / { 0x10, 0x01, 0x02 } (fail)
 *   2. Request a random number:          { 0x02, 0x08 }
 *        -> expect { 0x10, 0x02, 0x01, random[16] }
 *   3. Send the encrypted random:        { 0x03, FLAG, AES_ECB_NoPadding(key, random) }
 *        -> expect { 0x10, 0x03, 0x01 } (authenticated)
 *
 * FLAG is 0x08 on Mi Band 2/3 firmware and 0x00 on Mi Band 5 firmware
 * (observed by shravanrevanna.me). BleConnection tries 0x08 then 0x00.
 */
object MiBand5Auth {

    private const val CMD_SEND_KEY = 0x01.toByte()
    private const val CMD_REQUEST_RANDOM = 0x02.toByte()
    private const val CMD_SEND_ENCRYPTED_RANDOM = 0x03.toByte()
    private const val FLAG = 0x08.toByte()

    const val FLAG_LEGACY = 0x08.toByte()
    const val FLAG_BAND5 = 0x00.toByte()

    private const val RESP_OK = 0x01.toByte()
    private const val RESP_FAIL = 0x02.toByte()

    /** Build the "send auth key" frame. */
    fun sendKeyFrame(authKey: ByteArray): ByteArray {
        require(authKey.size == 16) { "Auth key must be exactly 16 bytes" }
        return byteArrayOf(CMD_SEND_KEY, FLAG) + authKey
    }

    /** Build the "request random number" frame. */
    fun requestRandomFrame(): ByteArray = byteArrayOf(CMD_REQUEST_RANDOM, FLAG)

    /** Build the "send encrypted random number" frame. */
    fun sendEncryptedRandomFrame(
        authKey: ByteArray,
        random: ByteArray,
        flag: Byte = FLAG_LEGACY
    ): ByteArray {
        val encrypted = aesEncryptEcbNoPadding(authKey, random)
        return byteArrayOf(CMD_SEND_ENCRYPTED_RANDOM, flag) + encrypted
    }

    /** Parse a notification received on the auth characteristic. */
    fun parseAuthResponse(data: ByteArray): AuthResponse {
        if (data.size < 3 || data[0] != 0x10.toByte()) return AuthResponse.Unknown
        return when (data[1]) {
            CMD_SEND_KEY ->
                if (data[2] == RESP_OK) AuthResponse.KeyAccepted else AuthResponse.KeyRejected
            CMD_REQUEST_RANDOM -> {
                if (data.size >= 3 + 16 && data[2] == RESP_OK) {
                    AuthResponse.RandomReceived(data.copyOfRange(3, 3 + 16))
                } else {
                    AuthResponse.Unknown
                }
            }
            CMD_SEND_ENCRYPTED_RANDOM ->
                if (data[2] == RESP_OK) AuthResponse.Authenticated else AuthResponse.AuthFailed
            else -> AuthResponse.Unknown
        }
    }

    private fun aesEncryptEcbNoPadding(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    sealed class AuthResponse {
        object KeyAccepted : AuthResponse()
        object KeyRejected : AuthResponse()
        data class RandomReceived(val random: ByteArray) : AuthResponse()
        object Authenticated : AuthResponse()
        object AuthFailed : AuthResponse()
        object Unknown : AuthResponse()
    }
}
