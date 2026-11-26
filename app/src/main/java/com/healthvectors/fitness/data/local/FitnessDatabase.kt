package com.healthvectors.fitness.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.healthvectors.fitness.data.local.dao.*
import com.healthvectors.fitness.data.local.entity.*
import java.time.LocalDate

class Converters {
    @TypeConverter fun toDate(v: String?): LocalDate? = v?.let(LocalDate::parse)
    @TypeConverter fun fromDate(d: LocalDate?): String? = d?.toString()
}

@Database(
    entities = [
        UserProfileEntity::class, BodyWeightEntity::class, BodyMeasurementEntity::class,
        ExerciseEntity::class, ExerciseMuscleEntity::class, RoutineEntity::class,
        RoutineDayEntity::class, RoutineExerciseEntity::class, WorkoutSessionEntity::class,
        WorkoutSetEntity::class, FoodEntity::class, MealEntryEntity::class, WaterEntryEntity::class,
        ProgressPhotoEntity::class
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)]
)
@TypeConverters(Converters::class)
abstract class FitnessDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun routineDao(): RoutineDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun nutritionDao(): NutritionDao
    abstract fun backupDao(): BackupDao
    abstract fun photoDao(): PhotoDao
}
