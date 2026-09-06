package com.example.miband5.ble

/**
 * Huami command bytes for the Mi Band 5.
 *
 * VERIFIED — from https://blog.shravanrevanna.me/i-reverse-engineered-my-2k-mi-band-to-read-my-heart-rate-live-on-my-mac-and-sync-with-smart-lights
 *  - HR continuous mode:  0x15 0x01 0x01 written to the HR control characteristic
 *  - HR manual measure:   0x15 0x02 0x01 (this is what actually powers the
 *                          optical sensor on Band 5 firmware)
 *  - Auth step 3 on Band 5: {0x03, 0x00, AES-ECB(random)} (flag 0x00; older
 *                          Mi Band 2/3 firmware uses 0x08 — see MiBand5Auth)
 *
 * UNCONFIRMED — must be ported from Gadgetbridge HuamiCommand.java before use:
 *  - CMD_FETCH_STEPS / CMD_FETCH_BATTERY / CMD_FETCH_ACTIVITY exact bytes
 *  - HR control STOP bytes (the blog confirms a STOP exists but doesn't give it)
 */
object MiBand5Commands {

    // Heart-rate control (verified)
    val HR_CONTROL_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x01)
    val HR_CONTROL_MANUAL = byteArrayOf(0x15, 0x02, 0x01)

    // Inferred from "sending a STOP kills the stream" — UNCONFIRMED
    val HR_CONTROL_STOP = byteArrayOf(0x15, 0x01, 0x00)

    // TODO(unconfirmed): fetch steps / battery / activity command bytes
}
