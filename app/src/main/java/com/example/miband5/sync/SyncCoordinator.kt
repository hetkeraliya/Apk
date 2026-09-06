package com.example.miband5.sync

import android.content.Context
import android.util.Log
import com.example.miband5.ble.BleConnection
import com.example.miband5.ble.MiBand5Commands
import com.example.miband5.ble.MiBand5DataParser
import com.example.miband5.ble.MiBand5Gatt
import com.example.miband5.data.AppDatabase
import com.example.miband5.data.entity.DailyStats
import com.example.miband5.data.entity.HrSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Runs after BLE auth succeeds: reads steps/battery, starts HR streaming,
 * writes everything into Room, prunes old samples.
 *
 * Zone minutes are a labeled approximation (age-based max HR placeholder —
 * Phase 1 features will compare against the user's own trailing baseline).
 */
class SyncCoordinator(
    private val context: Context,
    private val ble: BleConnection,
    private val onProgress: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = AppDatabase.getInstance(context)
    private val dailyDao = db.dailyStatsDao()
    private val hrDao = db.hrSampleDao()

    // Placeholder age for zone math — make configurable in Phase 3.
    private val maxHeartRate = 220 - 30

    fun start() {
        ble.notifyListener = { _, data -> onHeartRateSample(data) }
        scope.launch {
            try {
                syncStepsAndBattery()
                startHeartRate()
                pruneOldSamples()
            } catch (t: Throwable) {
                Log.e(TAG, "sync failed", t)
                onProgress("Sync error: ${t.message}")
            }
        }
    }

    private suspend fun syncStepsAndBattery() {
        onProgress("Syncing steps / battery…")

        val walk = ble.readCharacteristicSuspend(
            MiBand5Gatt.UUID_SERVICE_MIBAND2,
            MiBand5Gatt.UUID_CHARACTERISTIC_WALK
        )?.let { MiBand5DataParser.parseWalk(it) }

        val battery = ble.readCharacteristicSuspend(
            MiBand5Gatt.UUID_SERVICE_BATTERY,
            MiBand5Gatt.UUID_CHARACTERISTIC_BATTERY_LEVEL
        )?.firstOrNull()?.toInt()?.and(0xFF)

        val today = LocalDate.now().toString()
        val existing = dailyDao.getByDate(today) ?: DailyStats(date = today)
        dailyDao.upsert(
            existing.copy(
                steps = walk?.steps ?: existing.steps,
                distanceMeters = walk?.distanceMeters ?: existing.distanceMeters,
                calories = walk?.calories ?: existing.calories,
                batteryLast = battery ?: existing.batteryLast
            )
        )
        onProgress("Steps ${walk?.steps ?: "?"} · Battery ${battery ?: "?"}%")
    }

    private fun startHeartRate() {
        // Subscribe to the standard HR measurement characteristic, then send the
        // manual-measurement command (verified: this powers the optical sensor
        // on Band 5 firmware).
        ble.enableNotifications(
            MiBand5Gatt.UUID_SERVICE_HEART_RATE,
            MiBand5Gatt.UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT
        )
        // NOTE: this must be the standard Heart Rate Control Point (0x2A39)
        // under the standard Heart Rate service (0x180D) — it was previously
        // paired with the Huami-custom control UUID (0x0001), which doesn't
        // exist under that service, so this write silently found no
        // characteristic and never actually powered the sensor.
        ble.writeCharacteristic(
            MiBand5Gatt.UUID_SERVICE_HEART_RATE,
            MiBand5Gatt.UUID_CHARACTERISTIC_HEART_RATE_CONTROL,
            MiBand5Commands.HR_CONTROL_MANUAL
        )
        onProgress("Heart-rate streaming…")
    }

    fun onHeartRateSample(data: ByteArray) {
        val hr = MiBand5DataParser.parseHeartRate(data) ?: return
        scope.launch {
            val now = System.currentTimeMillis() / 1000
            hrDao.insert(HrSample(timestamp = now, heartRate = hr, rawIntensity = 0))

            val today = LocalDate.now().toString()
            val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            val endOfDay = startOfDay + 24 * 3600 - 1
            val samples = hrDao.getRange(startOfDay, endOfDay)
            val avg = if (samples.isNotEmpty()) samples.map { it.heartRate }.average().toInt() else hr

            val existing = dailyDao.getByDate(today) ?: DailyStats(date = today)
            val zone = zoneFor(hr)
            dailyDao.upsert(
                existing.copy(
                    heartRateMax = maxOf(existing.heartRateMax ?: 0, hr),
                    heartRateMin = if (existing.heartRateMin == null) hr else minOf(existing.heartRateMin, hr),
                    heartRateAvg = avg,
                    hrZoneLowMin = existing.hrZoneLowMin + if (zone == 0) 1 else 0,
                    hrZoneModerateMin = existing.hrZoneModerateMin + if (zone == 1) 1 else 0,
                    hrZoneHighMin = existing.hrZoneHighMin + if (zone == 2) 1 else 0
                )
            )
            onProgress("HR $hr bpm")
        }
    }

    /** Approximate zone from age-based max HR (labeled approximation). */
    private fun zoneFor(hr: Int): Int = when {
        hr >= (maxHeartRate * 0.7) -> 2 // high
        hr >= (maxHeartRate * 0.5) -> 1 // moderate
        else -> 0                       // low
    }

    private suspend fun pruneOldSamples() {
        val cutoff = System.currentTimeMillis() / 1000 - 90L * 24 * 3600
        hrDao.deleteOlderThan(cutoff)
    }

    fun stop() {
        ble.notifyListener = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "SyncCoordinator"
    }
}
