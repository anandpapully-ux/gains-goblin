package com.healthvectors.fitness

import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.domain.logic.*
import com.healthvectors.fitness.domain.model.*
import com.healthvectors.fitness.domain.plan.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlanCodecTest {
    private val bench = PlanExercise("my_bench", "My Bench", "Chest", listOf("Triceps"), "Barbell", incrementKg = 2.5)
    private val plan = Plan(
        "Test plan", "desc",
        listOf(
            PlanDay(1, "Push", DayType.HYPERTROPHY, 60, listOf(PlanItem("my_bench", 3, 6, 10, 120, 180, 1, 2, notes = "pause"))),
            PlanDay(4, "Run", DayType.CARDIO, 30, emptyList())
        ),
        listOf(bench)
    )

    @Test fun roundTrip() = assertEquals(plan, PlanCodec.decode(PlanCodec.encode(plan)))

    @Test fun missingWeekdaysBecomeRestDays() {
        val week = plan.fullWeek()
        assertEquals((1..7).toList(), week.map { it.weekday })
        assertEquals(DayType.REST, week[1].type)
        assertEquals("Push", week[0].title)
    }

    @Test fun optionalFieldsGetDefaults() {
        val p = PlanCodec.decode(
            """{"format":"gains-goblin-plan","version":1,"name":"x","days":[{"weekday":2,"title":"A",
               "exercises":[{"exercise":"push_up","sets":3,"reps":[10]}]}]}"""
        )
        val i = p.days.single().items.single()
        assertEquals(10, i.repMin); assertEquals(10, i.repMax)
        assertEquals(DayType.HYPERTROPHY, p.days.single().type)
        assertTrue(i.restMaxSec >= i.restMinSec)
    }

    @Test fun readsLegacyFormatAndCollapsesDuplicates() {
        val p = PlanCodec.decode(File("src/main/assets/workout_plan.json").readText())
        assertEquals(7, p.days.size)
        assertEquals(1, p.exercises.count { it.slug == "dumbbell_lateral_raises" })
        assertTrue(p.days.first { it.weekday == 4 }.items.any { it.slug == "dumbbell_lateral_raises" })
        assertEquals(4, p.days.count { it.type == DayType.HYPERTROPHY })
    }

    private fun assertRejected(json: String, fragment: String) {
        val e = assertThrows(PlanFormatException::class.java) { PlanCodec.decode(json) }
        assertTrue("'${e.message}' should mention '$fragment'", e.message!!.contains(fragment, ignoreCase = true))
    }

    @Test fun rejectsBadFiles() {
        assertRejected("not json", "not valid JSON")
        assertRejected("""{"hello":1}""", "not a Gains Goblin plan")
        assertRejected("""{"format":"gains-goblin-plan","version":99,"name":"x","days":[]}""", "newer version")
        assertRejected("""{"format":"gains-goblin-plan","name":"x","days":[{"weekday":9,"title":"A","exercises":[]}]}""", "weekday 9")
        assertRejected(
            """{"format":"gains-goblin-plan","name":"x","days":[{"weekday":1,"title":"A","exercises":[]},{"weekday":1,"title":"B","exercises":[]}]}""",
            "more than one day"
        )
        assertRejected(
            """{"format":"gains-goblin-plan","name":"x","days":[{"weekday":1,"title":"A","exercises":[{"exercise":"a","sets":3,"reps":[12,8]}]}]}""",
            "invalid range"
        )
        assertRejected(
            """{"format":"gains-goblin-plan","name":"x","days":[{"weekday":1,"title":"A","exercises":[{"exercise":"a","sets":0,"reps":[8,12]}]}]}""",
            "0 sets"
        )
    }

    @Test fun missingExercisesAreReported() {
        val p = plan.copy(exercises = emptyList())
        assertEquals(setOf("my_bench"), PlanCodec.missingExercises(p, setOf("other")))
        assertTrue(PlanCodec.missingExercises(p, setOf("my_bench")).isEmpty())
        assertTrue(PlanCodec.missingExercises(plan, emptySet()).isEmpty())
    }

    @Test fun ruleShortNames() {
        assertEquals(ProgressionRule.TIMED_CORE, PlanCodec.parseRule("timed"))
        assertEquals(ProgressionRule.ISOLATION_HIGH_REP_PROGRESSION, PlanCodec.parseRule("ISOLATION_HIGH_REP_PROGRESSION"))
    }
}

/** Guards the files shipped in assets/: every template must load and only use known exercises. */
class BundledAssetsTest {
    private val assets = File("src/main/assets")
    private val original = PlanCodec.decode(File(assets, "workout_plan.json").readText())
    private val library = PlanCodec.decodeLibrary(File(assets, "exercise_library.json").readText())

    @Test fun libraryIsValidAndHasNoDuplicates() {
        val all = original.exercises + library
        assertTrue(library.size >= 50)
        assertEquals(library.size, library.map { it.slug }.toSet().size)
        assertTrue("library must not redefine the original plan's exercises", library.none { l -> original.exercises.any { it.slug == l.slug } })
        // Names must be unique within the library and not clash with the original plan's exercises.
        val originalNames = original.exercises.map { it.name.lowercase() }.toSet()
        assertEquals(library.size, library.map { it.name.lowercase() }.toSet().size)
        assertTrue(library.none { it.name.lowercase() in originalNames })
        all.forEach { assertTrue(it.repUnit in PlanCodec.UNITS) }
    }

    @Test fun everyTemplateLoadsAndUsesKnownExercises() {
        val known = (original.exercises + library).map { it.slug }.toSet()
        val files = File(assets, "templates").listFiles()!!.filter { it.extension == "json" }
        assertTrue(files.size >= 4)
        files.forEach { f ->
            val p = PlanCodec.decode(f.readText())
            assertTrue("${f.name} uses unknown exercises", PlanCodec.missingExercises(p, known).isEmpty())
            assertEquals(7, p.fullWeek().size)
        }
    }
}

class ScheduleTest {
    private val slots = listOf(
        Schedule.Slot(10, 1, DayType.HYPERTROPHY), Schedule.Slot(11, 2, DayType.REST),
        Schedule.Slot(12, 3, DayType.HYPERTROPHY), Schedule.Slot(13, 5, DayType.CARDIO)
    )

    @Test fun rotationStartsAtFirstTrainingDay() = assertEquals(10L, Schedule.nextInRotation(slots, null)?.id)
    @Test fun rotationSkipsRestDays() = assertEquals(12L, Schedule.nextInRotation(slots, 10)?.id)
    @Test fun rotationWraps() = assertEquals(10L, Schedule.nextInRotation(slots, 13)?.id)
    @Test fun unknownLastDayRestarts() = assertEquals(10L, Schedule.nextInRotation(slots, 999)?.id)
    @Test fun noTrainingDays() = assertNull(Schedule.nextInRotation(listOf(Schedule.Slot(1, 1, DayType.REST)), null))

    @Test fun threeDayPlanSurvivesTheWeekend() {
        val gap = Schedule.streakGapDays(setOf(1, 3, 5))
        assertEquals(4L, gap) // Fri -> Mon is 3 days, plus one day of grace
        val today = java.time.LocalDate.of(2026, 11, 16) // a Monday
        val days = listOf(today, today.minusDays(3), today.minusDays(5), today.minusDays(7))
        assertEquals(4, StreakCalculator.streak(days, today, gap))
    }
    @Test fun sixDayPlanGap() = assertEquals(3L, Schedule.streakGapDays(setOf(1, 2, 3, 4, 5, 6)))
    @Test fun emptyPlanFallsBack() = assertEquals(2L, Schedule.streakGapDays(emptySet()))
}

class TargetCustomizationTest {
    private val base = Macros(2000, 160, 200, 60)

    @Test fun cyclingIsBalancedOverTheWeek() {
        for (n in 1..6) {
            val (up, down) = CalorieCalculator.cyclingFactors(n)
            assertEquals("n=$n", 7.0, n * up + (7 - n) * down, 1e-9)
            assertTrue(up >= 1.0 && down <= 1.0 && down >= 0.9 - 1e-9)
        }
        assertEquals(1.0 to 1.0, CalorieCalculator.cyclingFactors(0))
        assertEquals(1.0 to 1.0, CalorieCalculator.cyclingFactors(7))
    }

    @Test fun fourDaysMatchesTheOriginalNumbers() {
        val (up, down) = CalorieCalculator.cyclingFactors(4)
        assertEquals(1.06, up, 1e-9); assertEquals(0.92, down, 1e-9)
    }

    @Test fun noOverridesKeepsCalculated() = assertEquals(base, CalorieCalculator.withOverrides(base, null, null, null))

    @Test fun customCaloriesRecomputeFatAndCarbs() {
        val m = CalorieCalculator.withOverrides(base, 2400, null, null)
        assertEquals(2400, m.kcal); assertEquals(160, m.proteinG)
        assertEquals(67, m.fatG)
        assertEquals(2400.0, m.proteinG * 4.0 + m.carbsG * 4 + m.fatG * 9, 4.0)
    }

    @Test fun customProteinOnly() {
        val m = CalorieCalculator.withOverrides(base, null, 200, null)
        assertEquals(2000, m.kcal); assertEquals(200, m.proteinG); assertEquals(60, m.fatG)
        assertEquals(165, m.carbsG) // (2000 - 200*4 - 60*9) / 4
    }

    @Test fun carbsNeverNegative() = assertEquals(0, CalorieCalculator.withOverrides(base, 1000, 250, 80).carbsG)
}

class StepAndPlateTest {
    @Test fun stepFollowsEquipment() {
        assertEquals(2.5, Units.stepKg(UnitSystem.METRIC, "Barbell, Squat Rack"), 1e-9)
        assertEquals(5.0, Units.stepKg(UnitSystem.METRIC, "Cable Machine"), 1e-9)
        assertEquals(2.0, Units.stepKg(UnitSystem.METRIC, "Dumbbells, Flat Bench"), 1e-9)
        assertEquals(Units.lbToKg(10.0), Units.stepKg(UnitSystem.IMPERIAL, "Leg Press Machine"), 1e-9)
    }
    @Test fun exerciseOverrideWins() = assertEquals(1.25, Units.stepKg(UnitSystem.METRIC, "Barbell", 1.25), 1e-9)

    @Test fun poundPlates() {
        val r = PlateCalculator.platesPerSide(225.0, Units.defaultBar(UnitSystem.IMPERIAL), Units.defaultPlates(UnitSystem.IMPERIAL))
        assertEquals(listOf(45.0, 45.0), r.platesPerSide)
        assertEquals(0.0, r.remainderKg, 1e-9)
    }
}
