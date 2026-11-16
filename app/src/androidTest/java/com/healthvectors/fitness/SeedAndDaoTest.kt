package com.healthvectors.fitness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthvectors.fitness.data.local.FitnessDatabase
import com.healthvectors.fitness.data.local.entity.WorkoutSessionEntity
import com.healthvectors.fitness.data.plan.PlanAssets
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.data.repository.WorkoutRepository
import com.healthvectors.fitness.domain.logic.PrType
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.plan.PlanCodec
import com.healthvectors.fitness.domain.plan.PlanFormatException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device/emulator against an in-memory (unencrypted) database and the real assets. */
@RunWith(AndroidJUnit4::class)
class SeedAndDaoTest {
    private lateinit var db: FitnessDatabase
    private lateinit var ctx: Context
    private lateinit var assets: PlanAssets
    private lateinit var routines: RoutineRepository

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, FitnessDatabase::class.java).build()
        assets = PlanAssets(ctx)
        routines = RoutineRepository(db, db.routineDao(), assets)
    }

    @After fun tearDown() = db.close()

    @Test fun originalPlanCreatesTheRoutineAndSharesRepeatedExercises() = runBlocking {
        routines.createFromPlan(assets.original())
        val days = db.routineDao().observeDays().first()
        assertEquals(7, days.size)
        assertEquals(27, days.sumOf { db.routineDao().getItems(it.id).size })
        assertEquals(1, db.routineDao().allExercises().count { it.name == "Dumbbell Lateral Raises" })
        assertEquals(DayType.REST, days.first { it.dayOfWeek == 7 }.type)
    }

    @Test fun librarySyncIsIdempotentAndKeepsExistingRows() = runBlocking {
        routines.syncLibrary()
        val n = db.routineDao().allExercises().size
        assertTrue(n > 70)
        routines.syncLibrary()
        assertEquals(n, db.routineDao().allExercises().size)
    }

    @Test fun everyTemplateCanBeCreated() = runBlocking {
        val templates = assets.templates()
        assertTrue(templates.size >= 5)
        templates.forEach { routines.createFromPlan(it.plan) }
        val all = routines.routines.first()
        assertEquals(templates.size, all.size)
        assertEquals("only one plan is active", 1, all.count { it.isActive })
        assertEquals(templates.last().plan.name, all.single { it.isActive }.name)
    }

    @Test fun switchingPlansAndDeleting() = runBlocking {
        val a = routines.createFromPlan(assets.original())
        val b = routines.createFromPlan(assets.templates()[1].plan)
        assertEquals(b, routines.activeRoutine.first()!!.id)
        assertFalse("active plan can't be deleted", routines.delete(b))
        routines.activate(a)
        assertEquals(a, routines.activeRoutine.first()!!.id)
        assertEquals("Upper A (Chest + Upper-Back Emphasis)", db.routineDao().dayForWeekday(1)!!.title)
        assertTrue(routines.delete(b))
        assertEquals(1, routines.routines.first().size)
    }

    @Test fun movingADaySwapsWeekdays() = runBlocking {
        routines.createFromPlan(assets.original())
        val monday = db.routineDao().dayForWeekday(1)!!
        val sunday = db.routineDao().dayForWeekday(7)!!
        routines.moveDay(monday, 7)
        assertEquals(monday.title, db.routineDao().dayForWeekday(7)!!.title)
        assertEquals(sunday.title, db.routineDao().dayForWeekday(1)!!.title)
        assertEquals(7, routines.days.first().size)
    }

    @Test fun exportedPlanImportsElsewhereWithCustomExercises() = runBlocking {
        val id = routines.createFromPlan(assets.templates()[1].plan)
        val day = db.routineDao().daysOf(id).first { it.type == DayType.HYPERTROPHY }
        val custom = routines.createCustomExercise("Landmine Press", "Anterior Deltoids", "Barbell, Landmine")
        routines.addToDay(day.id, custom)
        val text = PlanCodec.encode(routines.exportPlan(id))

        // A different phone: fresh database without the custom exercise.
        val other = Room.inMemoryDatabaseBuilder(ctx, FitnessDatabase::class.java).build()
        try {
            val repo = RoutineRepository(other, other.routineDao(), assets)
            val newId = repo.importPlan(text)
            val newDay = other.routineDao().daysOf(newId).first { it.dayOfWeek == day.dayOfWeek }
            val names = other.routineDao().getItems(newDay.id).map { it.exercise.name }
            assertTrue("Landmine Press" in names)
            assertEquals(db.routineDao().getItems(day.id).size, names.size)
        } finally { other.close() }
    }

    @Test fun importWithUnknownExerciseFailsCleanly() = runBlocking {
        val bad = """{"format":"gains-goblin-plan","version":1,"name":"x","days":[{"weekday":1,"title":"A",
            "exercises":[{"exercise":"does_not_exist","sets":3,"reps":[8,12]}]}]}"""
        assertThrows(PlanFormatException::class.java) { runBlocking { routines.importPlan(bad) } }
        assertEquals(0, routines.routines.first().size)
    }

    @Test fun lastSessionAutofillAndPrDetection() = runBlocking {
        routines.createFromPlan(assets.original())
        val ex = db.routineDao().allExercises().first { it.slug == "flat_dumbbell_bench_press" }
        val repo = WorkoutRepository(db, db.workoutDao(), db.routineDao())
        val s1 = db.workoutDao().insertSession(WorkoutSessionEntity(routineDayId = null, startedAt = 1000))
        val first = repo.logSet(s1, ex.id, 0, 30.0, 8, null)
        assertTrue("first ever set is not a PR", first.prs.isEmpty())
        val s2 = db.workoutDao().insertSession(WorkoutSessionEntity(routineDayId = null, startedAt = 2000))
        assertEquals(30.0, db.workoutDao().lastSessionSets(ex.id, s2).single().weightKg, 0.0)
        val heavier = repo.logSet(s2, ex.id, 0, 32.0, 8, null)
        assertTrue(PrType.WEIGHT in heavier.prs)
    }

    @Test fun finishSummaryAndHistory() = runBlocking {
        routines.createFromPlan(assets.original())
        val day = db.routineDao().dayForWeekday(1)!!
        val ex = db.routineDao().getItems(day.id).first().exercise
        val repo = WorkoutRepository(db, db.workoutDao(), db.routineDao())
        val s = repo.startOrResume(day.id)
        repo.logSet(s, ex.id, 0, 20.0, 10, null)
        repo.logSet(s, ex.id, 1, 20.0, 8, null)
        val summary = repo.finish(s, 80.0, 5.0)!!
        assertEquals(2, summary.sets)
        assertEquals(360.0, summary.volumeKg, 1e-9)
        val row = repo.history.first().single()
        assertEquals(day.title, row.dayTitle)
        assertEquals(2, row.setCount)
        assertEquals(day.id, repo.lastDoneDay.first()!!.id)
        repo.deleteSession(s)
        assertTrue(repo.history.first().isEmpty())
    }
}
