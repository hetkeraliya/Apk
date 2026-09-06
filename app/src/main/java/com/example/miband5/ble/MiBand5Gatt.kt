package com.example.miband5.ble

import java.util.UUID

/**
 * GATT service / characteristic UUIDs for the Mi Band 5.
 *
 * Verified against Gadgetbridge's HuamiService.java
 * (https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/
 *  nodomain/freeyourgadget/gadgetbridge/devices/huami/HuamiService.java)
 * and the community reverse-engineering write-up
 * (https://medium.com/@_celianvdb/ble-reverse-engineering-mi-band-5-c3deed12c7).
 *
 * Huami base UUID pattern: 0000XXXX-0000-3512-2118-0009af100700
 */
object MiBand5Gatt {

    // ---- Huami services (verified) ----
    val UUID_SERVICE_MIBAND = UUID.fromString("0000FEE0-0000-3512-2118-0009AF100700")
    val UUID_SERVICE_MIBAND2 = UUID.fromString("0000FEE1-0000-3512-2118-0009AF100700")

    // ---- Huami characteristics (verified from HuamiService.java) ----
    val UUID_CHARACTERISTIC_AUTH = UUID.fromString("00000009-0000-3512-2118-0009AF100700")
    val UUID_CHARACTERISTIC_USER_SETTINGS = UUID.fromString("00000008-0000-3512-2118-0009AF100700")
    /** Walk/activity characteristic — steps/distance/calories (verified via Medium RE article). */
    val UUID_CHARACTERISTIC_WALK = UUID.fromString("00000007-0000-3512-2118-0009AF100700")
    /** Huami control characteristic — used for HR control on Band 5 (community-documented). */
    val UUID_CHARACTERISTIC_CONTROL = UUID.fromString("00000001-0000-3512-2118-0009AF100700")

    // ---- Standard BLE services (verified) ----
    val UUID_SERVICE_BATTERY = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val UUID_CHARACTERISTIC_BATTERY_LEVEL = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
    val UUID_CHARACTERISTIC_CURRENT_TIME = UUID.fromString("00002A2B-0000-1000-8000-00805F9B34FB")
    val UUID_SERVICE_HEART_RATE = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
    val UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
    /** Standard Heart Rate Control Point — lives under UUID_SERVICE_HEART_RATE, NOT the Huami service. */
    val UUID_CHARACTERISTIC_HEART_RATE_CONTROL = UUID.fromString("00002A39-0000-1000-8000-00805F9B34FB")

    // ---- Standard CCCD descriptor ----
    val UUID_DESCRIPTOR_CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // TODO(unconfirmed): Huami command bytes for fetch-steps / fetch-battery /
    // fetch-activity must be ported from Gadgetbridge HuamiCommand.java before
    // enabling those reads — not stated from memory here.
}
