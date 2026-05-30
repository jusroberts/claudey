package com.wiggletonabbey.wigglebot.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.wiggletonabbey.wigglebot.workers.RunningWeatherWorker
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.Date

private const val TAG = "AlarmScheduler"

object AlarmScheduler {

    const val ACTION_RUN_BRIEF         = "com.wiggletonabbey.wigglebot.ALARM_RUN_BRIEF"
    const val ACTION_COMMUTE           = "com.wiggletonabbey.wigglebot.ALARM_COMMUTE"
    const val ACTION_RUN_REMINDER      = "com.wiggletonabbey.wigglebot.ALARM_RUN_REMINDER"
    const val ACTION_AFTERNOON_COMMUTE = "com.wiggletonabbey.wigglebot.ALARM_AFTERNOON_COMMUTE"

    private val COMMUTE_DAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    )

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        // Cancel legacy WorkManager periodic jobs (one-time migration, idempotent after that).
        wm.cancelUniqueWork("run_brief")
        wm.cancelUniqueWork("commute_brief")
        wm.cancelUniqueWork("run_reminder")

        // Catch-up: if the device was off at 6am (alarm missed), fire the brief immediately
        // when the app or boot receiver calls schedule() in the 6–10am window.
        val now = ZonedDateTime.now()
        val today = now.toLocalDate()
        val todaySix = today.atTime(6, 0).atZone(now.zone)
        val todayTen = today.atTime(10, 0).atZone(now.zone)
        if (now.isAfter(todaySix) && now.isBefore(todayTen)) {
            wm.enqueueUniqueWork(
                "run_brief_catchup_$today",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RunningWeatherWorker>().build(),
            )
        }

        scheduleRunBrief(context)
        scheduleCommute(context)
        scheduleRunReminder(context)
        scheduleAfternoonCommute(context)
    }

    fun scheduleRunBrief(context: Context) =
        set(context, ACTION_RUN_BRIEF, nextOccurrenceMs(6, 0))

    fun scheduleCommute(context: Context) =
        set(context, ACTION_COMMUTE, nextCommuteMs(5, 30))

    fun scheduleRunReminder(context: Context) =
        set(context, ACTION_RUN_REMINDER, nextOccurrenceMs(18, 0))

    fun scheduleAfternoonCommute(context: Context) =
        set(context, ACTION_AFTERNOON_COMMUTE, nextWeekdayMs(15, 0))

    private fun set(context: Context, action: String, triggerAtMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        Log.d(TAG, "Scheduled $action at ${Date(triggerAtMs)}")
    }

    private fun nextOccurrenceMs(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var target = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
        if (!now.isBefore(target)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    private fun nextCommuteMs(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var candidate = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
        while (candidate.dayOfWeek !in COMMUTE_DAYS || !candidate.isAfter(now)) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }

    private fun nextWeekdayMs(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var candidate = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
        while (candidate.dayOfWeek !in WEEKDAYS || !candidate.isAfter(now)) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }
}
