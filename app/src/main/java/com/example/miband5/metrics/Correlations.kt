package com.example.miband5.metrics

import com.example.miband5.data.entity.DailyStats
import org.json.JSONArray

data class Correlation(val tag: String, val diffHours: Double, val daysWithTag: Int)

/**
 * Purely descriptive pattern-matching against this person's own logged
 * days — not a diagnosis, not a causal claim, just "here's what your own
 * data shows so far." Requires at least 3 days on each side of a
 * comparison before returning anything, so a single logged night can't
 * masquerade as a pattern (ported from the web app's same rule).
 */
object Correlations {

    val JOURNAL_TAGS = listOf(
        "Alcohol", "Late caffeine", "Late meal", "High stress", "Screen before bed", "Supplements"
    )

    private fun tagsOf(day: DailyStats): List<String> =
        try {
            val arr = JSONArray(day.journalTags)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }

    fun compute(allDays: List<DailyStats>): List<Correlation> {
        val withSleep = allDays.filter { it.sleepMinutes > 0 }
        val results = mutableListOf<Correlation>()

        for (tag in JOURNAL_TAGS) {
            val withTag = withSleep.filter { tag in tagsOf(it) }
            val withoutTag = withSleep.filter { tag !in tagsOf(it) }
            if (withTag.size < 3 || withoutTag.size < 3) continue

            val avgWith = withTag.map { it.sleepMinutes }.average() / 60.0
            val avgWithout = withoutTag.map { it.sleepMinutes }.average() / 60.0
            val diff = avgWith - avgWithout
            if (kotlin.math.abs(diff) < 0.2) continue

            results += Correlation(tag, diff, withTag.size)
        }
        return results
    }

    fun describe(c: Correlation): String {
        val dir = if (c.diffHours < 0) "less" else "more"
        val hrs = "%.1f".format(kotlin.math.abs(c.diffHours))
        return "On the ${c.daysWithTag} days you logged \"${c.tag},\" you slept ${hrs}h $dir on average than on days without it."
    }
}
