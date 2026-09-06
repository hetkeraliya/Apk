package com.example.miband5.metrics

import com.example.miband5.data.entity.DailyStats
import org.json.JSONArray
import java.time.LocalDate

data class Badge(val icon: String, val name: String, val earned: Boolean)

object Achievements {

    /** Consecutive days (ending today or the last day with data) at/above the step goal. */
    fun computeStreak(allDays: List<DailyStats>, stepGoal: Int): Int {
        if (allDays.isEmpty()) return 0
        val byDate = allDays.associateBy { it.date }
        val today = LocalDate.now()
        val sortedDates = byDate.keys.sorted()
        val lastDate = sortedDates.last()
        var cursor = if (LocalDate.parse(lastDate) > today) today.toString() else lastDate

        if (cursor == today.toString() && (byDate[today.toString()]?.steps ?: 0) < stepGoal) {
            cursor = today.minusDays(1).toString()
        }

        var streak = 0
        while (true) {
            val d = byDate[cursor] ?: break
            if (d.steps < stepGoal) break
            streak++
            cursor = LocalDate.parse(cursor).minusDays(1).toString()
        }
        return streak
    }

    private fun journalTagCount(day: DailyStats): Int =
        try { JSONArray(day.journalTags).length() } catch (e: Exception) { 0 }

    /**
     * @param muscleCounts this week's per-region tally, e.g. from
     *   MuscleSyncHelper.collectWeekCounts() — used only for the
     *   "Balanced Week" badge.
     */
    fun computeBadges(allDays: List<DailyStats>, stepGoal: Int, muscleCounts: Map<String, Int>): List<Badge> {
        val hasStreak = computeStreak(allDays, stepGoal) >= 3
        val has10k = allDays.any { it.steps >= 10000 }
        val wellRested = allDays.any { it.sleepMinutes >= 480 }
        val highStrain = allDays.any { d -> (HealthMetrics.computeStrain(d) ?: 0.0) >= 14.0 }
        val upper = (muscleCounts["chest"] ?: 0) + (muscleCounts["arms"] ?: 0) + (muscleCounts["shoulders"] ?: 0)
        val lower = (muscleCounts["quads"] ?: 0) + (muscleCounts["calves"] ?: 0)
        val balanced = upper > 0 && lower > 0
        val journaler = allDays.count { journalTagCount(it) > 0 } >= 5

        return listOf(
            Badge("\uD83D\uDD25", "3-Day Streak", hasStreak),
            Badge("\uD83D\uDCAF", "10K Day", has10k),
            Badge("\uD83C\uDF19", "Well Rested", wellRested),
            Badge("\u26A1", "High Strain", highStrain),
            Badge("\u2696\uFE0F", "Balanced Week", balanced),
            Badge("\uD83D\uDCD3", "Journaler", journaler),
        )
    }
}
