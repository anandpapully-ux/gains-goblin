package com.healthvectors.fitness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthvectors.fitness.data.local.FitnessDatabase
import com.healthvectors.fitness.data.local.entity.UserProfileEntity
import com.healthvectors.fitness.data.plan.PlanAssets
import com.healthvectors.fitness.data.repository.DataPorter
import com.healthvectors.fitness.data.repository.RoutineRepository
import com.healthvectors.fitness.data.repository.WorkoutRepository
import com.healthvectors.fitness.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private lateinit var ctx: Context
    private lateinit var a: FitnessDatabase
    private lateinit var b: FitnessDatabase

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        a = Room.inMemoryDatabaseBuilder(ctx, FitnessDatabase::class.java).build()
        b = Room.inMemoryDatabaseBuilder(ctx, FitnessDatabase::class.java).build()
    }

    @After fun tearDown() { a.close(); b.close() }

    private suspend fun populate(db: FitnessDatabase): Long {
        db.profileDao().upsert(
            UserProfileEntity(heightCm = 175.0, birthDate = LocalDate.of(1990, 5, 1), sex = Sex.FEMALE, activityLevel = ActivityLevel.MODERATE,
                goal = Goal.BULK, experience = Experience.ADVANCED, unitSystem = UnitSystem.IMPERIAL, weeklyRatePct = 0.5,
                createdAt = 7, customKcal = 2500)
        )
        db.profileDao().upsertWeight(com.healthvectors.fitness.data.local.entity.BodyWeightEntity(date = LocalDate.of(2026, 11, 1), weightKg = 70.0))
        val routines = RoutineRepository(db, db.routineDao(), PlanAssets(ctx))
        routines.createFromPlan(PlanAssets(ctx).templates()[2].plan)
        val day = db.routineDao().observeDays().first().first { it.type == DayType.HYPERTROPHY }
        val custom = routines.createCustomExercise("Sled Push", "Quadriceps", "Sled")
        routines.addToDay(day.id, custom)
        val w = WorkoutRepository(db, db.workoutDao(), db.routineDao())
        val s = w.startOrResume(day.id)
        w.logSet(s, custom.id, 0, 100.0, 10, 8.0)
        w.finish(s, 70.0, 5.0)
        return custom.id
    }

    @Test fun fullBackupRestoresEverythingOnAnotherDevice() = runBlocking {
        val customId = populate(a)
        val json = DataPorter(a).exportJson()
        assertTrue(DataPorter(b).isFullBackup(json))

        // The other phone already has its own data, which a restore replaces.
        RoutineRepository(b, b.routineDao(), PlanAssets(ctx)).createFromPlan(PlanAssets(ctx).original())
        val r = DataPorter(b).importJson(json)
        assertTrue(r.fullRestore)

        assertEquals(2500, b.profileDao().get()!!.customKcal)
        assertEquals(UnitSystem.IMPERIAL, b.profileDao().get()!!.unitSystem)
        assertEquals(a.routineDao().observeRoutines().first(), b.routineDao().observeRoutines().first())
        assertEquals(a.backupDao().items(), b.backupDao().items())
        assertEquals(a.workoutDao().allSets(), b.workoutDao().allSets())
        assertEquals("Sled Push", b.routineDao().getExercise(customId)!!.name)
        assertEquals(1, b.workoutDao().observeHistory().first().size)
        // Exporting again gives the same content.
        val again = DataPorter(b).exportJson()
        assertEquals(json.replace(Regex("\"exportedAt\": \\d+"), ""), again.replace(Regex("\"exportedAt\": \\d+"), ""))
    }

    @Test fun badBackupChangesNothing() = runBlocking {
        populate(a)
        val before = a.workoutDao().allSets()
        val broken = DataPorter(a).exportJson().replace("\"HYPERTROPHY\"", "\"NOT_A_TYPE\"")
        assertThrows(Exception::class.java) { runBlocking { DataPorter(a).importJson(broken) } }
        assertEquals(before, a.workoutDao().allSets())
        assertTrue(a.routineDao().observeRoutines().first().isNotEmpty())
    }

    @Test fun oldVersion1FileSkipsSetsForUnknownExercises() = runBlocking {
        val v1 = """{"version":1,"weights":[],"foods":[],"meals":[],"water":[],
            "sessions":[{"id":50,"routineDayId":null,"startedAt":1,"endedAt":2,"status":"DONE","caloriesBurned":null}],
            "sets":[{"id":1,"sessionId":50,"exerciseId":9999,"setIndex":0,"weightKg":40,"reps":8,"rpe":null,"isWarmup":false,"isPr":false,"completedAt":1}]}"""
        val r = DataPorter(b).importJson(v1)
        assertFalse(r.fullRestore)
        assertEquals(1, r.skipped)
        assertEquals(1, b.workoutDao().allSessions().size)
        assertTrue(b.workoutDao().allSets().isEmpty())
    }
}
