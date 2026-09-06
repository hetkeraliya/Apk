package com.example.miband5.data

import android.content.Context

/** Small app-wide settings. Same plain-SharedPreferences pattern as AuthKeyStore. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("miband5_settings", Context.MODE_PRIVATE)

    var stepGoal: Int
        get() = prefs.getInt(KEY_STEP_GOAL, 8000)
        set(value) = prefs.edit().putInt(KEY_STEP_GOAL, value).apply()

    /** Base URL of your own backend server that proxies AI Coach requests
     *  (the same Node/Express server the web app uses), e.g.
     *  "https://your-app.onrender.com". Leave blank to disable the Coach
     *  tab's network calls. */
    var coachServerUrl: String
        get() = prefs.getString(KEY_COACH_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_COACH_URL, value.trim()).apply()

    companion object {
        private const val KEY_STEP_GOAL = "step_goal"
        private const val KEY_COACH_URL = "coach_server_url"
    }
}
