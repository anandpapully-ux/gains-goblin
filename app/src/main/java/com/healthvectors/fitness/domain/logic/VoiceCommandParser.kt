package com.healthvectors.fitness.domain.logic

import com.healthvectors.fitness.core.util.Units

data class VoiceSet(val exerciseQuery: String, val weightKg: Double?, val reps: Int?)

/** Parses phrases such as "bench press, 60 kilos, 8 reps" or "lateral raise 12 kg for 15 reps". */
object VoiceCommandParser {
    private val repsRx = Regex("(\\d+)\\s*(reps?|repetitions?|times)")
    private val weightRx = Regex("(\\d+(?:\\.\\d+)?)\\s*(kilograms?|kilos?|kgs?|pounds?|lbs?)")
    private val noise = Regex("\\b(for|at|of|x|with|and|log|add)\\b")

    fun parse(text: String): VoiceSet? {
        var s = text.lowercase().replace(',', ' ')
        val reps = repsRx.find(s)?.groupValues?.get(1)?.toIntOrNull()
        s = repsRx.replace(s, " ")
        val wm = weightRx.find(s)
        var weight = wm?.groupValues?.get(1)?.toDoubleOrNull()
        val unit = wm?.groupValues?.get(2).orEmpty()
        if (weight != null && (unit.startsWith("pound") || unit.startsWith("lb"))) {
            weight = Units.lbToKg(weight)
        }
        s = weightRx.replace(s, " ")
        val name = noise.replace(s, " ").replace(Regex("[^a-z\\- ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (name.isEmpty() || (weight == null && reps == null)) return null
        return VoiceSet(name, weight, reps)
    }

    /** Best-matching exercise name by shared-word count, or null when nothing overlaps. */
    fun bestMatch(query: String, names: List<String>): String? {
        val q = query.lowercase().split(' ').filter { it.length > 2 }.toSet()
        return names.map { n -> n to n.lowercase().split(' ', '-', '(', ')').count { it in q } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
}

object StreakCalculator {
    /** Consecutive-session streak: a streak survives while sessions are at most [maxGapDays] apart. */
    fun streak(sessionDays: Collection<java.time.LocalDate>, today: java.time.LocalDate, maxGapDays: Long = 2): Int {
        val days = sessionDays.toSet().sortedDescending()
        if (days.isEmpty()) return 0
        if (java.time.temporal.ChronoUnit.DAYS.between(days.first(), today) > maxGapDays) return 0
        var count = 1
        for (i in 1 until days.size) {
            if (java.time.temporal.ChronoUnit.DAYS.between(days[i], days[i - 1]) <= maxGapDays) count++ else break
        }
        return count
    }
}
