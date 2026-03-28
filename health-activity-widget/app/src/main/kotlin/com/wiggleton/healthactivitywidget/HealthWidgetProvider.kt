package com.wiggleton.healthactivitywidget

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
import kotlin.random.Random

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
        const val WEEKS = 13

        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val repository = HealthConnectRepository(context)
            val prefs = WidgetPreferences(context)

            val dayColors: Map<LocalDate, List<Int>>

            if (!repository.isAvailable() || !repository.hasPermissions()) {
                val intent = Intent(context, PermissionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                views.setOnClickPendingIntent(
                    R.id.grid_image,
                    PendingIntent.getActivity(
                        context, appWidgetId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                dayColors = emptyMap()
            } else {
                val intent = Intent(context, ConfigActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                views.setOnClickPendingIntent(
                    R.id.grid_image,
                    PendingIntent.getActivity(
                        context, appWidgetId + 10_000, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )

                // Render from cache immediately so the widget is never blank on wake
                val cached = prefs.loadActivityCache()
                if (cached.isNotEmpty()) {
                    val cachedBitmap = GridRenderer.render(
                        toDayColors(cached, prefs), WEEKS, bitmapWidth(options), bitmapHeight(options)
                    )
                    views.setImageViewBitmap(R.id.grid_image, cachedBitmap)
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }

                // Fetch fresh data; only update widget and cache if we got a non-empty result
                val fresh = repository.getActivityData(WEEKS, prefs.showSteps, prefs.disabledExerciseTypes)
                if (fresh.isNotEmpty()) {
                    prefs.saveActivityCache(fresh)
                    dayColors = toDayColors(fresh, prefs)
                } else {
                    dayColors = toDayColors(cached, prefs)
                }
            }

            val bitmap = GridRenderer.render(dayColors, WEEKS, bitmapWidth(options), bitmapHeight(options))
            views.setImageViewBitmap(R.id.grid_image, bitmap)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun toDayColors(
            data: Map<LocalDate, Set<String>>,
            prefs: WidgetPreferences,
        ): Map<LocalDate, List<Int>> = data.mapValues { (date, keys) ->
            keys.toList()
                .shuffled(Random(date.toEpochDay()))
                .take(3)
                .map { key -> prefs.getActivityColor(key) }
        }

        private fun bitmapWidth(options: Bundle): Int {
            val density = Resources.getSystem().displayMetrics.density
            return (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250) * density)
                .toInt().coerceAtLeast(200)
        }

        private fun bitmapHeight(options: Bundle): Int {
            val density = Resources.getSystem().displayMetrics.density
            return (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110) * density)
                .toInt().coerceAtLeast(80)
        }
    }
}
