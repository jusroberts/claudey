package com.example.healthactivitywidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

class HealthWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            CoroutineScope(Dispatchers.IO).launch {
                updateWidget(context, appWidgetManager, id)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        WidgetUpdateWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        WidgetUpdateWorker.cancel(context)
    }

    companion object {
        // Number of weeks (columns) to display
        const val WEEKS = 26

        /**
         * Queries Health Connect for activity data, renders the grid bitmap, and
         * pushes updated [RemoteViews] to the given widget instance.
         *
         * Must be called from a coroutine context (suspend internally).
         */
        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val repository = HealthConnectRepository(context)

            val activeDays: Set<LocalDate>
            if (!repository.isAvailable() || !repository.hasPermissions()) {
                // Tap on the widget opens the permission-request activity
                val intent = Intent(context, PermissionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, appWidgetId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.grid_image, pendingIntent)
                activeDays = emptySet()
            } else {
                activeDays = repository.getActiveDays(WEEKS)
            }

            val bitmap = GridRenderer.render(activeDays, WEEKS, bitmapWidth(options), bitmapHeight(options))
            views.setImageViewBitmap(R.id.grid_image, bitmap)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        // Convert the widget's reported dp size to pixels for the bitmap dimensions.
        private fun bitmapWidth(options: Bundle): Int {
            val density = Resources.getSystem().displayMetrics.density
            val dp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            return (dp * density).toInt().coerceAtLeast(200)
        }

        private fun bitmapHeight(options: Bundle): Int {
            val density = Resources.getSystem().displayMetrics.density
            val dp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            return (dp * density).toInt().coerceAtLeast(80)
        }
    }
}
