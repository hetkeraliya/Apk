package com.example.miband5.metrics

import com.example.miband5.data.entity.DailyStats
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Direct port of the web app's scoring logic (public/app.js: computeDailyScore,
 * computeStrainScore). Formulas are intentionally simple and transparent —
 * never presented as medical or as a reproduction of any proprietary
 * algorithm. Heart-rate comparisons are always against this person's own
 * trailing baseline, never a fixed "healthy" number, since normal resting
 * HR varies enormously person to person.
 */

data class ScorePart(val name: String, val pct: Float)
data class ScoreResult(val score: Int, val label: String, val parts: List<ScorePart>)

object HealthMetrics {

    /**
     * @param day the selected day
     * @param stepGoal the user's daily step goal
     * @param baselineHrAvgs up to the last 14 days' heartRateAvg values,
     *   excluding [day] itself, from days that have a heart-rate reading.
     */
    fun computeScore(day: DailyStats, stepGoal: Int, baselineHrAvgs: List<Int>): ScoreResult? {
        val parts = mutableListOf<ScorePart>()
        val weights = mutableListOf<Float>()

        if (day.steps > 0) {
            val pct = max(0f, min(1f, day.steps.toFloat() / stepGoal))
            parts += ScorePart("Steps", pct)
            weights += 0.4f
        }

        val sleepHrs = day.sleepMinutes / 60f
        if (day.sleepMinutes > 0) {
            val distanceFromWindow = when {
                sleepHrs < 7f -> 7f - sleepHrs
                sleepHrs > 9f -> sleepHrs - 9f
                else -> 0f
            }
            val pct = max(0f, 1f - distanceFromWindow / 3f)
            parts += ScorePart("Sleep", pct)
            weights += 0.3f
        }

        val hr = day.heartRateAvg
        if (hr != null && hr > 0 && baselineHrAvgs.size >= 2) {
            val baseline = baselineHrAvgs.average().toFloat()
            val ratio = baseline / hr
            val pct = max(0f, min(1f, if (ratio > 1f) 1f else 1f - (1f - ratio) * 2f))
            parts += ScorePart("Heart rate", pct)
            weights += 0.3f
        }

        if (parts.isEmpty()) return null

        val totalWeight = weights.sum()
        val score = (parts.indices.sumOf { i -> (parts[i].pct * weights[i]).toDouble() } / totalWeight * 100)
            .roundToInt()

        val label = when {
            score >= 80 -> "Strong day overall."
            score >= 60 -> "Solid day, room to push a bit."
            score >= 40 -> "Below your usual — one or two areas lagging."
            else -> "Quiet day across the board."
        }
        return ScoreResult(score, label, parts)
    }

    /**
     * 0-21ish, loosely modeled on the public description of WHOOP's Strain —
     * not a reproduction of any proprietary formula. Needs today's HR-zone
     * minutes, which are computed and stored on DailyStats at sync time
     * (see SyncCoordinator).
     */
    fun computeStrain(day: DailyStats): Double? {
        val total = day.hrZoneLowMin + day.hrZoneModerateMin + day.hrZoneHighMin
        if (total <= 0) return null
        val weighted = day.hrZoneLowMin * 0.5 + day.hrZoneModerateMin * 1.5 + day.hrZoneHighMin * 3.0
        return min(21.0, ((weighted / 120.0) * 21.0 * 10).roundToInt() / 10.0)
    }

    fun strainLabel(score: Double?): String = when {
        score == null -> "Not enough heart-rate data for this day"
        score >= 14 -> "High exertion day"
        score >= 8 -> "Moderate exertion"
        else -> "Light day"
    }
}
