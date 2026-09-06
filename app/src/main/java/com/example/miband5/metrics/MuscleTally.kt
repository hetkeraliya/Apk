package com.example.miband5.metrics

import com.example.miband5.data.entity.ManualWorkout
import com.example.miband5.data.entity.Workout
import com.example.miband5.muscle.MuscleMapping
import com.example.miband5.muscle.MuscleSet
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

data class MuscleTally(val counts: Map<String, Int>, val secondary: Set<String>)

object MuscleTallyCalculator {

    private fun parseMuscleSet(json: String): MuscleSet? {
        return try {
            val obj = JSONObject(json)
            val primary = obj.optJSONArray("primary")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            val secondary = obj.optJSONArray("secondary")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            if (primary.isEmpty() && secondary.isEmpty()) null else MuscleSet(primary, secondary)
        } catch (e: Exception) {
            null
        }
    }

    /** exercises JSON on a ManualWorkout/tagged Workout: [{"name":..,"primary":[..],"secondary":[..],"sets":..,"reps":..}] */
    private fun parseExerciseListMuscles(json: String): List<MuscleSet> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val primary = obj.optJSONArray("primary")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val secondary = obj.optJSONArray("secondary")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                if (primary.isEmpty() && secondary.isEmpty()) null else MuscleSet(primary, secondary)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun collectWeekTally(workouts: List<Workout>, manualWorkouts: List<ManualWorkout>): MuscleTally {
        val counts = MuscleMapping.REGIONS.associateWith { 0 }.toMutableMap()
        val secondary = mutableSetOf<String>()

        fun tally(set: MuscleSet?) {
            if (set == null) return
            for (m in set.primary) counts[m]?.let { counts[m] = it + 1 }
            secondary += set.secondary
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val weekStart = today.minusDays(6)

        for (w in workouts) {
            val day = java.time.Instant.ofEpochSecond(w.startTime).atZone(zone).toLocalDate()
            if (day.isBefore(weekStart) || day.isAfter(today)) continue
            val auto = MuscleMapping.forWorkoutName(w.name.ifBlank { w.kind })
            if (auto != null) {
                tally(auto)
            } else {
                // Manually tagged (e.g. a generic "Exercise"/"Workout" entry the
                // user filled in via the exercise picker).
                parseExerciseListMuscles(w.muscleTags).forEach { tally(it) }
            }
        }

        for (mw in manualWorkouts) {
            val day = try { LocalDate.parse(mw.date) } catch (e: Exception) { continue }
            if (day.isBefore(weekStart) || day.isAfter(today)) continue
            if (mw.label == "Running") {
                tally(MuscleMapping.forWorkoutName("Running"))
            }
            parseExerciseListMuscles(mw.exercises).forEach { tally(it) }
        }

        return MuscleTally(counts, secondary)
    }
}
