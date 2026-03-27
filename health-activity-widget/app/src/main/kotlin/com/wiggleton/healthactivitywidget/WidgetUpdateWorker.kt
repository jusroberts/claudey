package com.wiggleton.healthactivitywidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs periodically in the background to refresh all active widget instances.
 * Scheduled when the first widget is added and cancelled when the last is removed.
 */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val component = ComponentName(applicationContext, HealthWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { id ->
            HealthWidgetProvider.updateWidget(applicationContext, manager, id)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "health_widget_periodic_update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
