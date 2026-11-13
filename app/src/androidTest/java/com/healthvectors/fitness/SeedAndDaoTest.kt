package com.healthvectors.fitness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthvectors.fitness.data.local.FitnessDatabase
import com.healthvectors.fitness.data.local.entity.WorkoutSessionEntity
import com.healthvectors.fitness.data.local.seed.PlanSeeder
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.data.repository.WorkoutRepository
import com.healthvectors.fitness.domain.logic.PrType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device/emulator: seeds the real JSON asset into an in-memory (unencrypted) database. */
@RunWith(AndroidJUnit4::class)
class SeedAndDaoTest {
    private lateinit var db: FitnessDatabase
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, FitnessDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    @Test fun seedingCreatesTheRoutineAndSharesRepeatedExercises() = runBlocking {
        PlanSeeder(ctx, db).seedIfEmpty()
        PlanSeeder(ctx, db).seedIfEmpty() // idempotent
        val days = db.routineDao().observeDays().first()
        assertEquals(7, days.size)
        assertEquals(24, db.routineDao().allExercises().size)
        val itemCount = days.sumOf { db.routineDao().getItems(it.id).size }
        assertEquals(27, itemCount)
        // Lateral raises appear on Day 1 and Day 4 but are one library exercise.
        assertEquals(1, db.routineDao().allExercises().count { it.name == "Dumbbell Lateral Raises" })
    }

    @Test fun lastSessionAutofillAndPrDetection() = runBlocking {
        PlanSeeder(ctx, db).seedIfEmpty()
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
}
