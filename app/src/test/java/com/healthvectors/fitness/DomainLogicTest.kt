package com.healthvectors.fitness

import com.healthvectors.fitness.domain.logic.*
import com.healthvectors.fitness.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class OneRepMaxTest {
    @Test fun epleyKnownValue() = assertEquals(80.0 * (1 + 8 / 30.0), OneRepMax.epley(80.0, 8), 1e-9)
    @Test fun singleRepIsWeight() = assertEquals(100.0, OneRepMax.estimate(100.0, 1)!!, 1e-9)
    @Test fun highRepsAreNotEstimated() = assertNull(OneRepMax.estimate(40.0, 20))
    @Test fun invalidInputIsNull() { assertNull(OneRepMax.estimate(0.0, 5)); assertNull(OneRepMax.estimate(50.0, 0)) }
    @Test fun inverseRoundTrips() {
        val e = OneRepMax.epley(60.0, 10)
        assertEquals(60.0, OneRepMax.weightForReps(e, 10), 1e-9)
    }
    @Test fun brzyckiCloseToEpleyAtFiveReps() =
        assertEquals(OneRepMax.epley(100.0, 5), OneRepMax.brzycki(100.0, 5), 5.0)
}

class ProgressionTest {
    private val compound = ProgressionTarget(3, 8, 12, ProgressionRule.COMPOUND_DOUBLE_PROGRESSION)

    @Test fun noHistory() = assertEquals(Suggestion.NoHistory, Progression.suggest(emptyList(), compound, 2.0))

    @Test fun allSetsAtTopIncreasesWeight() {
        val last = List(3) { SetResult(20.0, 12) }
        assertEquals(Suggestion.IncreaseWeight(22.0), Progression.suggest(last, compound, 2.0))
    }
    @Test fun oneSetShortKeepsWeight() {
        val last = listOf(SetResult(20.0, 12), SetResult(20.0, 11), SetResult(20.0, 10))
        val s = Progression.suggest(last, compound, 2.0)
        assertTrue(s is Suggestion.KeepWeight)
        assertEquals(11, (s as Suggestion.KeepWeight).targetReps)
    }
    @Test fun belowRangeDecreases() {
        val last = listOf(SetResult(30.0, 6), SetResult(30.0, 6), SetResult(30.0, 5))
        assertEquals(Suggestion.DecreaseWeight(28.0), Progression.suggest(last, compound, 2.0))
    }
    @Test fun missingSetsPreventsIncrease() {
        val s = Progression.suggest(listOf(SetResult(20.0, 12), SetResult(20.0, 12)), compound, 2.0)
        assertFalse(s is Suggestion.IncreaseWeight)
    }
    @Test fun isolationUsesWiderCeiling() {
        val t = ProgressionTarget(3, 12, 25, ProgressionRule.ISOLATION_HIGH_REP_PROGRESSION)
        assertTrue(Progression.suggest(List(3) { SetResult(6.0, 20) }, t, 2.0) is Suggestion.KeepWeight)
        assertEquals(Suggestion.IncreaseWeight(8.0), Progression.suggest(List(3) { SetResult(6.0, 25) }, t, 2.0))
    }
    @Test fun timedCoreHasNoSuggestion() {
        val t = ProgressionTarget(3, 30, 60, ProgressionRule.TIMED_CORE)
        assertEquals(Suggestion.NoHistory, Progression.suggest(listOf(SetResult(0.0, 60)), t, 2.0))
    }
}

class DeloadDetectorTest {
    @Test fun twoVeryHardSessions() = assertTrue(DeloadDetector.needsDeload(listOf(8.0, 9.5, 10.0)))
    @Test fun risingHighRpe() = assertTrue(DeloadDetector.needsDeload(listOf(9.0, 9.2, 9.4)))
    @Test fun normalEffort() = assertFalse(DeloadDetector.needsDeload(listOf(8.0, 8.5, 8.0)))
    @Test fun notEnoughData() = assertFalse(DeloadDetector.needsDeload(listOf(10.0)))
}

class CalorieCalculatorTest {
    @Test fun mifflinMale() = assertEquals(1780.0, CalorieCalculator.bmr(Sex.MALE, 80.0, 180.0, 30), 1e-9)
    @Test fun mifflinFemale() = assertEquals(10 * 60 + 6.25 * 165 - 5 * 30 - 161.0, CalorieCalculator.bmr(Sex.FEMALE, 60.0, 165.0, 30), 1e-9)

    @Test fun cutDeficitMatchesWeeklyRate() {
        // 80 kg * 0.6% = 0.48 kg/week -> 0.48 * 7700 / 7 = 528 kcal/day
        val k = CalorieCalculator.targetKcal(2800.0, Goal.CUT, 80.0, 0.6, Sex.MALE)
        assertEquals(2800.0 - 528.0, k, 1e-6)
    }
    @Test fun cutDeficitIsCappedAt25Percent() {
        val k = CalorieCalculator.targetKcal(2000.0, Goal.CUT, 150.0, 1.0, Sex.MALE)
        assertEquals(1500.0, k, 1e-6)
    }
    @Test fun cutNeverBelowFloor() =
        assertEquals(1200.0, CalorieCalculator.targetKcal(1400.0, Goal.CUT, 60.0, 1.0, Sex.FEMALE), 1e-6)
    @Test fun bulkAddsSurplus() = assertEquals(2800.0, CalorieCalculator.targetKcal(2500.0, Goal.BULK, 80.0, 0.0, Sex.MALE), 1e-6)

    @Test fun macrosAddUpToCalories() {
        val m = CalorieCalculator.macros(2200.0, 80.0, Goal.CUT)
        assertEquals(160, m.proteinG)
        assertEquals(2200.0, m.proteinG * 4.0 + m.carbsG * 4 + m.fatG * 9, 20.0)
    }
    @Test fun trainingDayGetsMoreRestDayLess() {
        val base = Macros(2000, 160, 200, 60)
        val t = CalorieCalculator.adjustForDay(base, DayType.HYPERTROPHY)
        val r = CalorieCalculator.adjustForDay(base, DayType.REST)
        assertTrue(t.kcal > base.kcal && r.kcal < base.kcal)
        assertEquals(160, t.proteinG); assertEquals(160, r.proteinG)
    }
    @Test fun workoutBurn() = assertEquals(5.0 * 80 * 0.75, CalorieCalculator.workoutBurn(5.0, 80.0, 45.0), 1e-9)
}

class GymToolsTest {
    @Test fun platesFor100() {
        val r = PlateCalculator.platesPerSide(100.0)
        assertEquals(listOf(25.0, 15.0), r.platesPerSide)
        assertEquals(0.0, r.remainderKg, 1e-9)
    }
    @Test fun onlyBar() = assertTrue(PlateCalculator.platesPerSide(20.0).platesPerSide.isEmpty())
    @Test fun unloadableRemainder() = assertTrue(PlateCalculator.platesPerSide(21.0).remainderKg > 0)

    @Test fun warmupsAscendAndStayBelowWorkingWeight() {
        val w = WarmupGenerator.generate(40.0)
        assertEquals(3, w.size)
        assertTrue(w.zipWithNext().all { (a, b) -> a.weightKg < b.weightKg })
        assertTrue(w.all { it.weightKg < 40.0 })
    }
    @Test fun noWarmupForLightLoads() = assertTrue(WarmupGenerator.generate(3.0).isEmpty())

    @Test fun firstEverSetIsNotAPr() = assertTrue(PrDetector.detect(50.0, 8, null).isEmpty())
    @Test fun heavierWeightIsPr() {
        val prs = PrDetector.detect(32.0, 8, ExerciseBest(30.0, OneRepMax.epley(30.0, 8)))
        assertTrue(PrType.WEIGHT in prs && PrType.ESTIMATED_1RM in prs)
    }
    @Test fun repPrAtSameWeight() {
        val prs = PrDetector.detect(30.0, 10, ExerciseBest(30.0, OneRepMax.epley(30.0, 8)))
        assertEquals(listOf(PrType.ESTIMATED_1RM), prs)
    }
    @Test fun equalIsNotPr() = assertTrue(PrDetector.detect(30.0, 8, ExerciseBest(30.0, OneRepMax.epley(30.0, 8))).isEmpty())
}

class VoiceParserTest {
    @Test fun example() {
        val p = VoiceCommandParser.parse("bench press, 60 kilos, 8 reps")!!
        assertEquals("bench press", p.exerciseQuery); assertEquals(60.0, p.weightKg!!, 1e-9); assertEquals(8, p.reps)
    }
    @Test fun poundsConvertToKg() {
        val p = VoiceCommandParser.parse("lateral raise 20 pounds for 15 reps")!!
        assertEquals(9.07, p.weightKg!!, 0.01); assertEquals(15, p.reps)
    }
    @Test fun decimalWeight() = assertEquals(22.5, VoiceCommandParser.parse("curl 22.5 kg 10 reps")!!.weightKg!!, 1e-9)
    @Test fun gibberishIsNull() = assertNull(VoiceCommandParser.parse("hello there"))
    @Test fun matchesExerciseByWords() =
        assertEquals("Flat Dumbbell Bench Press", VoiceCommandParser.bestMatch("bench press", listOf("Dumbbell Hammer Curls", "Flat Dumbbell Bench Press")))
    @Test fun noMatch() = assertNull(VoiceCommandParser.bestMatch("zzz", listOf("Plank")))
}

class StreakTest {
    private val today = LocalDate.of(2026, 11, 12)
    @Test fun consecutiveAndGapTolerant() =
        assertEquals(3, StreakCalculator.streak(listOf(today, today.minusDays(2), today.minusDays(4)), today))
    @Test fun brokenByLongGap() =
        assertEquals(1, StreakCalculator.streak(listOf(today, today.minusDays(5)), today))
    @Test fun staleStreakIsZero() = assertEquals(0, StreakCalculator.streak(listOf(today.minusDays(6)), today))
    @Test fun empty() = assertEquals(0, StreakCalculator.streak(emptyList(), today))
}
