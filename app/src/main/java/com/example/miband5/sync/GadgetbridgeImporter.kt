package com.example.miband5.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.miband5.data.AppDatabase
import com.example.miband5.data.entity.DailyStats
import com.example.miband5.data.entity.HrSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipFile

/**
 * One-time migration path: imports a Gadgetbridge SQLite export
 * (or its backup .zip) into Room.
 *
 * The activity-sample table name varies by which device Gadgetbridge is
 * talking to (MI_BAND_ACTIVITY_SAMPLE, HUAMI_EXTENDED_ACTIVITY_SAMPLE,
 * DEVICE_ACTIVITY_SAMPLE, etc. depending on version/device) -- there is no
 * single fixed name to rely on. This detects the right table at runtime by
 * finding one with the columns actually needed, the same approach already
 * proven against a real export in this project's web app
 * (findActivitySampleTable in public/app.js).
 *
 * RAW_KIND (ActivityKind): 1=activity, 2=light sleep, 3=deep sleep, 4=not worn.
 * Sleep-stage data from Huami bands is a best-effort heuristic — stored as-is.
 */
class GadgetbridgeImporter(private val context: Context) {

    private data class TableCols(val table: String, val ts: String, val steps: String, val hr: String, val kind: String?, val intensity: String?)

    private fun findActivityTable(db: SQLiteDatabase): TableCols? {
        val tableNames = mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            while (c.moveToNext()) tableNames.add(c.getString(0))
        }
        // Prefer names that look like an activity-sample table, but fall
        // back to checking every table -- Gadgetbridge's naming isn't
        // standardized across device types.
        val ordered = tableNames.sortedByDescending { it.contains("ACTIVITY_SAMPLE", ignoreCase = true) }

        for (table in ordered) {
            val cols = mutableListOf<String>()
            try {
                db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
                    while (c.moveToNext()) cols.add(c.getString(c.getColumnIndexOrThrow("name")))
                }
            } catch (e: Exception) {
                continue
            }
            val upper = cols.map { it.uppercase() }
            val ts = cols.getOrNull(upper.indexOf("TIMESTAMP")) ?: continue
            val steps = cols.getOrNull(upper.indexOf("STEPS")) ?: continue
            val hr = cols.getOrNull(upper.indexOf("HEART_RATE"))
                ?: cols.getOrNull(upper.indexOf("HEARTRATE"))
                ?: cols.getOrNull(upper.indexOf("HR"))
                ?: continue
            val kind = cols.getOrNull(upper.indexOf("RAW_KIND"))
            val intensity = cols.getOrNull(upper.indexOf("RAW_INTENSITY"))
                ?: cols.getOrNull(upper.indexOf("INTENSITY"))
            return TableCols(table, ts, steps, hr, kind, intensity)
        }
        return null
    }

    suspend fun import(sourcePath: String): ImportResult = withContext(Dispatchers.IO) {
        val dbPath = extractIfZip(sourcePath)
        val db = AppDatabase.getInstance(context)
        val dest = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val found = findActivityTable(dest)
                ?: throw IllegalStateException("No table with TIMESTAMP/STEPS/HEART_RATE columns found in this export.")

            val samples = mutableListOf<HrSample>()
            val daily = mutableMapOf<String, DailyStats>()
            var rows = 0

            val kindSelect = found.kind?.let { "\"$it\"" } ?: "NULL"
            val intensitySelect = found.intensity?.let { "\"$it\"" } ?: "NULL"

            dest.rawQuery(
                "SELECT \"${found.ts}\", $kindSelect, \"${found.steps}\", $intensitySelect, \"${found.hr}\" " +
                    "FROM \"${found.table}\" ORDER BY \"${found.ts}\" ASC",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    rows++
                    val ts = c.getLong(0)
                    val kind = if (c.isNull(1)) -1 else c.getInt(1)
                    val steps = c.getInt(2)
                    val intensity = if (c.isNull(3)) 0 else c.getInt(3)
                    val hr = c.getInt(4)

                    if (hr in 1..253) {
                        samples.add(HrSample(timestamp = ts, heartRate = hr, rawIntensity = intensity))
                    }

                    val date = Instant.ofEpochSecond(ts)
                        .atZone(ZoneId.systemDefault()).toLocalDate().toString()
                    val stats = daily.getOrPut(date) { DailyStats(date = date) }
                    daily[date] = when (kind) {
                        2 -> stats.copy(
                            sleepMinutes = stats.sleepMinutes + 1,
                            lightSleepMinutes = stats.lightSleepMinutes + 1
                        )
                        3 -> stats.copy(
                            sleepMinutes = stats.sleepMinutes + 1,
                            deepSleepMinutes = stats.deepSleepMinutes + 1
                        )
                        else -> stats.copy(steps = stats.steps + steps)
                    }
                }
            }

            db.hrSampleDao().insertAll(samples)
            daily.values.forEach { db.dailyStatsDao().upsert(it) }

            ImportResult(rowsRead = rows, hrSamples = samples.size, days = daily.size)
        } finally {
            dest.close()
        }
    }

    /** If the source is a Gadgetbridge backup .zip, extract the .db inside. */
    private fun extractIfZip(path: String): String {
        if (!path.endsWith(".zip", ignoreCase = true)) return path
        val dir = File(context.cacheDir, "gb_import").apply {
            deleteRecursively()
            mkdirs()
        }
        ZipFile(path).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (!entry.isDirectory && entry.name.endsWith(".db", ignoreCase = true)) {
                    val out = File(dir, entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    return out.absolutePath
                }
            }
        }
        return path
    }

    data class ImportResult(val rowsRead: Int, val hrSamples: Int, val days: Int)
}

