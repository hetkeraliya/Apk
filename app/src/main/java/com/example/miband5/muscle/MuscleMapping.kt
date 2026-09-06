package com.example.miband5.muscle

/**
 * Muscle-group mapping, ported directly from the web app's verified logic
 * (matched by exercise science, not guesswork — e.g. running is legs/core,
 * not an arm exercise, even though it can feel full-body).
 *
 * Matching is done against the workout's own name/kind text rather than
 * the band's internal numeric activity-type codes, which aren't documented
 * and vary by firmware. This is more robust and was already validated
 * against all activity types this band's own settings screen lists
 * (Outdoor Running, Walking, Treadmill, Outdoor/Indoor Cycling, Elliptical,
 * Swimming, Freestyle, Skipping rope, Rowing Machine, Yoga).
 */
data class MuscleSet(val primary: List<String>, val secondary: List<String> = emptyList())

object MuscleMapping {

    val REGIONS = listOf("shoulders", "chest", "arms", "core", "quads", "calves")

    private val AUTO_MAP: Map<String, MuscleSet> = mapOf(
        "Running" to MuscleSet(listOf("quads", "calves", "core"), listOf("glutes", "hamstrings")),
        "Walking" to MuscleSet(listOf("quads", "calves"), listOf("glutes")),
        "Cycling" to MuscleSet(listOf("quads", "calves"), listOf("glutes", "hamstrings")),
        "Elliptical" to MuscleSet(listOf("quads", "calves", "core"), listOf("glutes")),
        "Swimming" to MuscleSet(listOf("chest", "arms", "shoulders", "core"), listOf("back")),
        "Hiking" to MuscleSet(listOf("quads", "calves", "core"), listOf("glutes", "hamstrings")),
        "Rowing" to MuscleSet(listOf("arms", "core", "quads"), listOf("back")),
        "JumpRope" to MuscleSet(listOf("calves", "core")),
        "Yoga" to MuscleSet(listOf("core"))
    )

    /** Ordered so the first match wins; "Freestyle" deliberately matches nothing. */
    private val NAME_RULES: List<Pair<Regex, String>> = listOf(
        Regex("treadmill|running|\\brun\\b|jog", RegexOption.IGNORE_CASE) to "Running",
        Regex("walk", RegexOption.IGNORE_CASE) to "Walking",
        Regex("elliptical", RegexOption.IGNORE_CASE) to "Elliptical",
        Regex("cycl|\\bbik(e|ing)\\b", RegexOption.IGNORE_CASE) to "Cycling",
        Regex("swim", RegexOption.IGNORE_CASE) to "Swimming",
        Regex("hik", RegexOption.IGNORE_CASE) to "Hiking",
        Regex("rowing", RegexOption.IGNORE_CASE) to "Rowing",
        Regex("skip|jump.?rope", RegexOption.IGNORE_CASE) to "JumpRope",
        Regex("yoga", RegexOption.IGNORE_CASE) to "Yoga"
    )

    /** Returns null for Freestyle / anything unrecognized -> caller should prompt for exercises. */
    fun forWorkoutName(name: String): MuscleSet? {
        for ((rule, key) in NAME_RULES) {
            if (rule.containsMatchIn(name)) return AUTO_MAP[key]
        }
        return null
    }

    val EXERCISE_LIBRARY: List<Pair<String, MuscleSet>> = listOf(
        "Push-ups" to MuscleSet(listOf("chest", "arms", "core"), listOf("shoulders")),
        "Pull-ups" to MuscleSet(listOf("arms", "shoulders"), listOf("back")),
        "Bicep Curls" to MuscleSet(listOf("arms")),
        "Squats" to MuscleSet(listOf("quads", "core"), listOf("glutes")),
        "Lunges" to MuscleSet(listOf("quads", "calves"), listOf("glutes")),
        "Deadlifts" to MuscleSet(listOf("core"), listOf("hamstrings", "glutes", "back")),
        "Bench Press" to MuscleSet(listOf("chest", "arms"), listOf("shoulders")),
        "Shoulder Press" to MuscleSet(listOf("shoulders", "arms")),
        "Plank" to MuscleSet(listOf("core")),
        "Jump Rope" to MuscleSet(listOf("calves", "core")),
        "Rowing Machine" to MuscleSet(listOf("arms", "core", "quads"), listOf("back")),
        "Yoga / Stretch" to MuscleSet(listOf("core"))
    )
}
