package com.healthvectors.fitness

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthvectors.fitness.data.local.FitnessDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrading from 1.0.x must keep every row and add the new nullable columns. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FitnessDatabase::class.java)

    @Test fun v1ToV2KeepsData() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO user_profile (id, heightCm, birthDate, sex, activityLevel, goal, experience, unitSystem, weeklyRatePct, createdAt) VALUES (1, 180.0, '1995-01-01', 'MALE', 'LIGHT', 'CUT', 'INTERMEDIATE', 'METRIC', 0.6, 1)")
            execSQL("INSERT INTO exercise (id, slug, name, primaryMuscle, equipment, instructions, progressionRule, repUnit, isUnilateral, alternatives, isCustom) VALUES (5, 'bench', 'Bench', 'Chest', 'Barbell', '', 'COMPOUND_DOUBLE_PROGRESSION', 'reps', 0, '', 0)")
            execSQL("INSERT INTO workout_session (id, routineDayId, startedAt, endedAt, status, notes, caloriesBurned) VALUES (1, NULL, 1000, 2000, 'DONE', '', 100.0)")
            execSQL("INSERT INTO workout_set (id, sessionId, exerciseId, setIndex, weightKg, reps, rpe, isWarmup, isPr, completedAt) VALUES (1, 1, 5, 0, 60.0, 8, NULL, 0, 0, 1500)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true)
        db.query("SELECT heightCm, customKcal FROM user_profile").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(180.0, c.getDouble(0), 0.0); assertTrue(c.isNull(1))
        }
        db.query("SELECT name, incrementKg FROM exercise WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Bench", c.getString(0)); assertTrue(c.isNull(1))
        }
        db.query("SELECT weightKg FROM workout_set").use { c -> assertTrue(c.moveToFirst()); assertEquals(60.0, c.getDouble(0), 0.0) }
        db.close()
    }

    @Test fun v2ToV3AddsPhotoTableAndKeepsData() {
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO body_weight_entry (id, date, weightKg, source) VALUES (1, '2026-10-01', 80.0, 'MANUAL')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 3, true)
        db.query("SELECT weightKg FROM body_weight_entry").use { c -> assertTrue(c.moveToFirst()); assertEquals(80.0, c.getDouble(0), 0.0) }
        db.execSQL("INSERT INTO progress_photo (takenOn, pose, fileName, width, height, weightKg, note, createdAt) VALUES ('2026-10-01', 'FRONT', 'x', 1200, 1600, 80.0, '', 1)")
        db.query("SELECT pose FROM progress_photo").use { c -> assertTrue(c.moveToFirst()); assertEquals("FRONT", c.getString(0)) }
        db.close()
    }
}
