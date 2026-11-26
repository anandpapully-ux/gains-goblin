package com.healthvectors.fitness.di

import android.content.Context
import androidx.room.Room
import com.healthvectors.fitness.data.local.DbKeyProvider
import com.healthvectors.fitness.data.local.FitnessDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context, keys: DbKeyProvider): FitnessDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(ctx, FitnessDatabase::class.java, "fitness.db")
            .openHelperFactory(SupportOpenHelperFactory(keys.passphrase()))
            .build()
    }

    @Provides fun profileDao(db: FitnessDatabase) = db.profileDao()
    @Provides fun routineDao(db: FitnessDatabase) = db.routineDao()
    @Provides fun workoutDao(db: FitnessDatabase) = db.workoutDao()
    @Provides fun nutritionDao(db: FitnessDatabase) = db.nutritionDao()
    @Provides fun photoDao(db: FitnessDatabase) = db.photoDao()

    /** Injectable so tests can pin "today". */
    @Provides fun clock(): Clock = Clock.systemDefaultZone()
}
