package com.wiggletonabbey.wigglebot.workers

import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wiggletonabbey.wigglebot.notifications.NotificationHelper
import com.wiggletonabbey.wigglebot.schedule.HealthConnectHelper
import com.wiggletonabbey.wigglebot.service.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val TAG = "RunReminderWorker"

class RunReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val settingsRepo = SettingsRepository(applicationContext)
    private val healthConnect = HealthConnectHelper(applicationContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val isRunDay = healthConnect.isLikelyRunDay()
        val hasRun   = healthConnect.hasRunToday()
        Log.d(TAG, "isLikelyRunDay=$isRunDay hasRunToday=$hasRun")

        if (!isRunDay) {
            Log.d(TAG, "Not a likely run day — skipping reminder")
            return Result.success()
        }

        if (hasRun) {
            Log.d(TAG, "Already ran today — no reminder needed")
            return Result.success()
        }

        // Fetch weather to personalise the nudge.
        val loc = lastKnownLocation()
        val (title, body) = if (loc != null) {
            val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
            val url = "$serverUrl/api/brief/run?lat=${loc.first}&lon=${loc.second}"
            fetchWeatherNudge(url) ?: defaultNudge()
        } else {
            defaultNudge()
        }

        NotificationHelper.postRunReminder(applicationContext, title, body)
        Log.d(TAG, "Reminder posted")
        return Result.success()
    }

    private fun fetchWeatherNudge(url: String): Pair<String, String>? = runCatching {
        val body = http.newCall(Request.Builder().url(url).build()).execute()
            .use { it.body?.string() ?: "" }

        val obj = json.parseToJsonElement(body).jsonObject
        val good = obj["good"]?.jsonPrimitive?.boolean ?: true
        val weatherBody = obj["body"]?.jsonPrimitive?.content ?: ""

        if (good) {
            "🏃 Still time for a run!" to "You haven't run yet. $weatherBody"
        } else {
            "🏃 Run day reminder" to "You haven't run yet — but conditions aren't great. $weatherBody"
        }
    }.getOrNull()

    private fun defaultNudge() =
        "🏃 Still time for a run!" to "You haven't logged a run yet today."

    private fun lastKnownLocation(): Pair<Double, Double>? {
        val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstNotNullOfOrNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }
            ?.let { it.latitude to it.longitude }
    }
}
