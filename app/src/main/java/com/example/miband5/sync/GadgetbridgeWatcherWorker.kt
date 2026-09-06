package com.example.miband5.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.miband5.data.GadgetbridgeWatchSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * No third-party automation app needed: this watches the same folder
 * Gadgetbridge's own "Auto export" feature writes to (Settings -> Auto
 * export, in Gadgetbridge), and imports anything newer than what was last
 * imported. Entirely self-contained -- just Gadgetbridge and this app.
 *
 * Requires the user to grant folder access once, via the Storage Access
 * Framework (see MainActivity's folder-picker launcher) -- Android doesn't
 * allow silent background access to arbitrary folders on API 29+, so this
 * one-time permission grant is unavoidable, not a design choice.
 */
class GadgetbridgeWatcherWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = GadgetbridgeWatchSettings(applicationContext)
        val uriString = settings.watchFolderUri
        if (uriString == null) {
            Log.d(TAG, "No watch folder set yet -- skipping")
            return@withContext Result.success()
        }

        val treeUri = Uri.parse(uriString)
        val folder = DocumentFile.fromTreeUri(applicationContext, treeUri)
        if (folder == null || !folder.isDirectory) {
            Log.w(TAG, "Saved folder is no longer accessible")
            return@withContext Result.success()
        }

        // Newest file in the folder wins -- Gadgetbridge's auto-export
        // typically overwrites or timestamps a single file per run.
        val newest = folder.listFiles()
            .filter { it.isFile && (it.name?.endsWith(".zip", true) == true || it.name?.endsWith(".db", true) == true || it.name.isNullOrEmpty().not()) }
            .maxByOrNull { it.lastModified() }

        if (newest == null) {
            Log.d(TAG, "No files found in watch folder")
            return@withContext Result.success()
        }

        if (newest.lastModified() <= settings.lastImportedTimestamp) {
            Log.d(TAG, "Newest file (${newest.name}) already imported -- nothing to do")
            return@withContext Result.success()
        }

        try {
            // GadgetbridgeImporter needs a real filesystem path; copy the
            // SAF-provided content into cache first.
            val tempFile = File(applicationContext.cacheDir, "gb_watch_${newest.name}")
            applicationContext.contentResolver.openInputStream(newest.uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                Log.w(TAG, "Could not open ${newest.name}")
                return@withContext Result.retry()
            }

            val result = GadgetbridgeImporter(applicationContext).import(tempFile.absolutePath)
            tempFile.delete()

            settings.lastImportedTimestamp = newest.lastModified()
            settings.lastImportedName = newest.name
            Log.i(TAG, "Auto-imported ${newest.name}: ${result.rowsRead} rows, ${result.days} days")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-import failed for ${newest.name}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "GadgetbridgeWatcher"
        private const val WORK_NAME = "gadgetbridge-auto-import"

        fun schedule(context: Context, intervalHours: Long = 6) {
            val request = PeriodicWorkRequestBuilder<GadgetbridgeWatcherWorker>(intervalHours, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<GadgetbridgeWatcherWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
