package com.healthvectors.fitness.domain.logic

import com.healthvectors.fitness.domain.model.DayType

/** Scheduling rules that depend on the user's own plan rather than a fixed week. */
object Schedule {
    data class Slot(val id: Long, val order: Int, val type: DayType)

    /**
     * Rotation mode: the training day after the last finished one (wrapping around), skipping rest days.
     * With no history, or when the last day is no longer in the plan, it starts from the first training day.
     */
    fun nextInRotation(slots: List<Slot>, lastDoneDayId: Long?): Slot? {
        val training = slots.filter { it.type != DayType.REST }.sortedBy { it.order }
        if (training.isEmpty()) return null
        val i = training.indexOfFirst { it.id == lastDoneDayId }
        return if (i < 0) training.first() else training[(i + 1) % training.size]
    }

    /**
     * How many days may pass between sessions before a streak breaks: the longest planned gap between
     * training weekdays (wrapping over the weekend) plus one day of grace. A Mon/Wed/Fri plan allows 4,
     * so the weekend no longer breaks the streak.
     */
    fun streakGapDays(trainingWeekdays: Set<Int>): Long {
        val d = trainingWeekdays.filter { it in 1..7 }.sorted()
        if (d.isEmpty()) return 2
        val gaps = d.zipWithNext { a, b -> b - a } + (d.first() + 7 - d.last())
        return (gaps.max() + 1).toLong().coerceIn(2, 8)
    }
}
