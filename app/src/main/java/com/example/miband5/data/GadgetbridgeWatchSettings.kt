package com.example.miband5.data

import android.content.Context

/** Same plain-SharedPreferences pattern as AuthKeyStore. */
class GadgetbridgeWatchSettings(context: Context) {

    private val prefs = context.getSharedPreferences("gb_watch_settings", Context.MODE_PRIVATE)

    /** SAF tree URI (as a string) for the folder Gadgetbridge auto-exports into. */
    var watchFolderUri: String?
        get() = prefs.getString(KEY_URI, null)
        set(value) = prefs.edit().putString(KEY_URI, value).apply()

    /** lastModified() of the most recently imported file, to detect new exports. */
    var lastImportedTimestamp: Long
        get() = prefs.getLong(KEY_LAST_TS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_TS, value).apply()

    var lastImportedName: String?
        get() = prefs.getString(KEY_LAST_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_NAME, value).apply()

    companion object {
        private const val KEY_URI = "watch_folder_uri"
        private const val KEY_LAST_TS = "last_imported_ts"
        private const val KEY_LAST_NAME = "last_imported_name"
    }
}
