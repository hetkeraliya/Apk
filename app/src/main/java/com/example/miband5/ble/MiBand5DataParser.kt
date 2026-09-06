package com.example.miband5.ble

/**
 * Parsers for Mi Band 5 characteristic payloads.
 *
 * Walk characteristic (0x0007) layout — VERIFIED from
 * https://medium.com/@_celianvdb/ble-reverse-engineering-mi-band-5-c3deed12c7
 *   Steps:     bytes [3..0] little-endian
 *   Distance:  bytes [7..4] little-endian (meters)
 *   Calories:  bytes [11..8] little-endian (kcal)
 */
object MiBand5DataParser {

    data class WalkData(val steps: Int, val distanceMeters: Int, val calories: Int)

    fun parseWalk(data: ByteArray): WalkData? {
        if (data.size < 12) return null
        return WalkData(
            steps = leInt(data, 0),
            distanceMeters = leInt(data, 4),
            calories = leInt(data, 8)
        )
    }

    /**
     * Standard BLE Heart Rate Measurement (0x2A37) — VERIFIED format:
     * byte 0 = flags (bit0 set => 16-bit HR value), then the HR value.
     * Mi Band 5 reports an averaged BPM (no RR-interval data over BLE).
     */
    fun parseHeartRate(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        return if (flags and 0x01 != 0) {
            if (data.size < 3) null
            else (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            if (data.size < 2) null else data[1].toInt() and 0xFF
        }
    }

    private fun leInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
