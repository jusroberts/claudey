package com.wiggletonabbey.wigglebot.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wiggletonabbey.wigglebot.workers.CommuteWorker
import com.wiggletonabbey.wigglebot.workers.RunReminderWorker
import com.wiggletonabbey.wigglebot.workers.RunningWeatherWorker

private const val TAG = "BriefAlarmReceiver"

class BriefAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wm = WorkManager.getInstance(context)
        when (intent.action) {
            AlarmScheduler.ACTION_RUN_BRIEF -> {
                Log.d(TAG, "Run brief alarm fired")
                wm.enqueue(OneTimeWorkRequestBuilder<RunningWeatherWorker>().build())
                AlarmScheduler.scheduleRunBrief(context)
            }
            AlarmScheduler.ACTION_COMMUTE -> {
                Log.d(TAG, "Commute alarm fired")
                wm.enqueue(OneTimeWorkRequestBuilder<CommuteWorker>().build())
                AlarmScheduler.scheduleCommute(context)
            }
            AlarmScheduler.ACTION_RUN_REMINDER -> {
                Log.d(TAG, "Run reminder alarm fired")
                wm.enqueue(OneTimeWorkRequestBuilder<RunReminderWorker>().build())
                AlarmScheduler.scheduleRunReminder(context)
            }
            AlarmScheduler.ACTION_AFTERNOON_COMMUTE -> {
                Log.d(TAG, "Afternoon commute alarm fired")
                wm.enqueue(
                    OneTimeWorkRequestBuilder<CommuteWorker>()
                        .setInputData(workDataOf("is_afternoon" to true))
                        .build()
                )
                AlarmScheduler.scheduleAfternoonCommute(context)
            }
        }
    }
}
