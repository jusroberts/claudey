package com.wiggletonabbey.wigglebot.schedule

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wiggletonabbey.wigglebot.workers.CommuteWorker
import com.wiggletonabbey.wigglebot.workers.RunReminderWorker
import com.wiggletonabbey.wigglebot.workers.RunningWeatherWorker
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object WorkScheduler {

    private val COMMUTE_DAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        // Running brief: 6am daily.
        // CommuteWorker takes over at 5:30am on Mon/Thu, so RunningWeatherWorker
        // checks internally whether to suppress itself on commute days.
        wm.enqueueUniquePeriodicWork(
            "run_brief",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RunningWeatherWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayUntil(6, 0), TimeUnit.MILLISECONDS)
                .build()
        )

        // Commute brief: 5:30am Mon/Thu.
        // We schedule two independent workers with a 7-day period so each fires
        // on the right day — WorkManager doesn't support day-of-week natively,
        // so the workers check internally and no-op if it's the wrong day.
        wm.enqueueUniquePeriodicWork(
            "commute_brief",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CommuteWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(delayUntilNextCommute(5, 30), TimeUnit.MILLISECONDS)
                .build()
        )

        // Run reminder: 6pm daily.
        wm.enqueueUniquePeriodicWork(
            "run_reminder",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RunReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayUntil(18, 0), TimeUnit.MILLISECONDS)
                .build()
        )
    }

    /** Milliseconds until the next occurrence of hour:minute today or tomorrow. */
    private fun delayUntil(hour: Int, minute: Int): Long {
        val now = LocalDateTime.now()
        var target = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
        if (!now.isBefore(target)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis().coerceAtLeast(0)
    }

    /** Milliseconds until the next Mon or Thu at the given time. */
    private fun delayUntilNextCommute(hour: Int, minute: Int): Long {
        val now = LocalDateTime.now()
        var candidate = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
        repeat(14) {
            if (candidate.dayOfWeek in COMMUTE_DAYS && candidate.isAfter(now)) return@repeat
            candidate = candidate.plusDays(1)
        }
        return Duration.between(now, candidate).toMillis().coerceAtLeast(0)
    }
}
