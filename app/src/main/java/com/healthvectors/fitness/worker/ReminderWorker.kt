package com.healthvectors.fitness.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.healthvectors.fitness.MainActivity
import com.healthvectors.fitness.data.local.FitnessDatabase
import com.healthvectors.fitness.domain.model.DayType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint { fun database(): FitnessDatabase }

/** Daily reminder. Stays silent on rest days and when a workout was already finished today. */
class ReminderWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val db = EntryPointAccessors.fromApplication(ctx, ReminderEntryPoint::class.java).database()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val trainedToday = db.workoutDao().observeDoneSessionsSinceBlocking(start)
        val day = db.routineDao().dayForWeekday(today.dayOfWeek.value)
        if (trainedToday || day == null || day.type == DayType.REST) return Result.success()
        notify("Time to train", "Today: ${day.title}")
        return Result.success()
    }

    private fun notify(title: String, text: String) {
        if (Build_VERSION_TIRAMISU() && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }

    @Suppress("FunctionName")
    private fun Build_VERSION_TIRAMISU() = android.os.Build.VERSION.SDK_INT >= 33

    companion object {
        private const val CHANNEL = "reminders"
        private const val NAME = "daily_reminder"

        fun schedule(ctx: Context, hour: Int) {
            val now = LocalDateTime.now()
            var next = now.with(LocalTime.of(hour, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next)).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }
}
