package com.example.miband5.ui.metrics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.miband5.coach.CoachApi
import com.example.miband5.data.AppDatabase
import com.example.miband5.data.AppSettings
import com.example.miband5.data.entity.DailyStats
import com.example.miband5.data.entity.ManualWorkout
import com.example.miband5.metrics.Achievements
import com.example.miband5.metrics.Badge
import com.example.miband5.metrics.Correlations
import com.example.miband5.metrics.HealthMetrics
import com.example.miband5.metrics.MuscleTallyCalculator
import com.example.miband5.metrics.ScoreResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class ChatMessage(val fromUser: Boolean, val text: String)

data class MetricsUiState(
    val today: DailyStats? = null,
    val allDays: List<DailyStats> = emptyList(),
    val score: ScoreResult? = null,
    val strain: Double? = null,
    val strainLabel: String = "",
    val badges: List<Badge> = emptyList(),
    val correlationLines: List<String> = emptyList(),
    val muscleCounts: Map<String, Int> = emptyMap(),
    val muscleSecondary: Set<String> = emptySet(),
    val stepGoal: Int = 8000,
    val journalTags: List<String> = emptyList()
)

class MetricsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val settings = AppSettings(app)

    private val _state = MutableStateFlow(MetricsUiState())
    val state: StateFlow<MetricsUiState> = _state.asStateFlow()

    val chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val coachBusy = MutableStateFlow(false)
    val coachError = MutableStateFlow<String?>(null)

    private var selectedDate: LocalDate = LocalDate.now()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val allDays = db.dailyStatsDao().getAll()
            val byDate = allDays.associateBy { it.date }
            val today = byDate[selectedDate.toString()]

            val baselineHr = allDays
                .filter { it.date != selectedDate.toString() && (it.heartRateAvg ?: 0) > 0 }
                .sortedByDescending { it.date }
                .take(14)
                .mapNotNull { it.heartRateAvg }

            val score = today?.let { HealthMetrics.computeScore(it, settings.stepGoal, baselineHr) }
            val strain = today?.let { HealthMetrics.computeStrain(it) }

            val workouts = db.workoutDao().getAll()
            val manualWorkouts = db.manualWorkoutDao().getAll()
            val tally = MuscleTallyCalculator.collectWeekTally(workouts, manualWorkouts)

            val badges = Achievements.computeBadges(allDays, settings.stepGoal, tally.counts)
            val correlations = Correlations.compute(allDays).map { Correlations.describe(it) }

            val currentTags = try {
                val arr = JSONArray(today?.journalTags ?: "[]")
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) { emptyList() }

            _state.value = MetricsUiState(
                today = today,
                allDays = allDays,
                score = score,
                strain = strain,
                strainLabel = HealthMetrics.strainLabel(strain) + (today?.let { " for ${it.date}" } ?: ""),
                badges = badges,
                correlationLines = correlations,
                muscleCounts = tally.counts,
                muscleSecondary = tally.secondary,
                stepGoal = settings.stepGoal,
                journalTags = currentTags
            )
        }
    }

    fun selectDate(date: LocalDate) {
        selectedDate = date
        refresh()
    }

    // ---------------- Journal ----------------

    fun toggleJournalTag(tag: String, checked: Boolean) {
        viewModelScope.launch {
            val date = selectedDate.toString()
            val existing = db.dailyStatsDao().getByDate(date) ?: DailyStats(date = date)
            val tags = try {
                val arr = JSONArray(existing.journalTags)
                (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
            } catch (e: Exception) { mutableSetOf() }
            if (checked) tags.add(tag) else tags.remove(tag)
            db.dailyStatsDao().upsert(existing.copy(journalTags = JSONArray(tags.toList()).toString()))
            refresh()
        }
    }

    fun saveNote(text: String) {
        viewModelScope.launch {
            val date = selectedDate.toString()
            val existing = db.dailyStatsDao().getByDate(date) ?: DailyStats(date = date)
            db.dailyStatsDao().upsert(existing.copy(notes = text))
        }
    }

    // ---------------- Manual workout logging (Phase 1) ----------------

    /** exercises: list of (name, primaryMuscles, secondaryMuscles, sets, reps) already
     *  chosen by the user in the exercise picker. */
    fun logManualWorkout(label: String, exercises: List<PickedExercise>) {
        viewModelScope.launch {
            val exercisesJson = JSONArray(exercises.map { it.toJson() }).toString()
            db.manualWorkoutDao().insert(
                ManualWorkout(
                    date = LocalDate.now().toString(),
                    label = label,
                    exercises = exercisesJson
                )
            )
            refresh()
        }
    }

    fun tagWorkout(workoutId: Long, exercises: List<PickedExercise>) {
        viewModelScope.launch {
            db.workoutDao().updateMuscleTags(workoutId, JSONArray(exercises.map { it.toJson() }).toString())
            refresh()
        }
    }

    // ---------------- AI Coach ----------------

    fun askCoach(question: String) {
        val baseUrl = settings.coachServerUrl
        if (baseUrl.isBlank()) {
            coachError.value = "Set your Coach server URL in Settings first (the same server the web app uses)."
            return
        }
        chatMessages.value = chatMessages.value + ChatMessage(fromUser = true, text = question)
        coachBusy.value = true
        coachError.value = null

        viewModelScope.launch {
            val s = _state.value
            val context = JSONObject().apply {
                put("stepGoal", s.stepGoal)
                put("today", s.today?.let { dayToJson(it) } ?: JSONObject())
                put("last7Days", JSONArray(s.allDays.takeLast(7).map { dayToJson(it) }))
                put("strain", s.strain ?: JSONObject.NULL)
                put("muscleCountsThisWeek", JSONObject(s.muscleCounts))
            }
            when (val result = CoachApi(baseUrl).ask(context, question)) {
                is CoachApi.Result.Success -> {
                    chatMessages.value = chatMessages.value + ChatMessage(fromUser = false, text = result.reply)
                }
                is CoachApi.Result.Failure -> {
                    coachError.value = result.message
                }
            }
            coachBusy.value = false
        }
    }

    fun askForTomorrowsPlan() = askCoach("Based on my data, what should tomorrow look like?")

    private fun dayToJson(d: DailyStats): JSONObject = JSONObject().apply {
        put("date", d.date)
        put("steps", d.steps)
        put("heartRateAvg", d.heartRateAvg ?: JSONObject.NULL)
        put("sleepMinutes", d.sleepMinutes)
    }
}

data class PickedExercise(
    val name: String,
    val primary: List<String>,
    val secondary: List<String>,
    val sets: Int?,
    val reps: Int?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("primary", JSONArray(primary))
        put("secondary", JSONArray(secondary))
        put("sets", sets ?: JSONObject.NULL)
        put("reps", reps ?: JSONObject.NULL)
    }
}
