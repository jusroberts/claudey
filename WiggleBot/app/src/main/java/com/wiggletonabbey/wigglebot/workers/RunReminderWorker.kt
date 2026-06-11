package com.wiggletonabbey.wigglebot.workers

import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wiggletonabbey.wigglebot.notifications.NotificationHelper
import com.wiggletonabbey.wigglebot.schedule.HealthConnectHelper
import com.wiggletonabbey.wigglebot.schedule.WorkerOutcomeStore
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
        // Always Result.success() so WorkManager doesn't retry-spam; the outcome
        // (including errors) is recorded for the Settings diagnostics panel.
        val outcome = runCatching { postReminder() }.getOrElse { e ->
            Log.e(TAG, "Run reminder failed", e)
            "error: ${e.message?.take(120) ?: e.javaClass.simpleName}"
        }
        Log.d(TAG, "Outcome: $outcome")
        runCatching {
            WorkerOutcomeStore.record(applicationContext, WorkerOutcomeStore.KEY_RUN_REMINDER, outcome)
        }.onFailure { Log.e(TAG, "Failed to record outcome", it) }
        return Result.success()
    }

    /** Runs the reminder logic and returns the outcome string to record. */
    private suspend fun postReminder(): String {
        val settings = settingsRepo.settings.first()
        val hasRun = healthConnect.hasRunToday()
        Log.d(TAG, "hasRunToday=$hasRun useRunPredictor=${settings.useRunPredictor}")

        if (hasRun) {
            Log.d(TAG, "Already ran today — no reminder needed")
            return "skipped: already ran today"
        }

        // The predictor gate is opt-in: only suppress the nudge when the user
        // explicitly enabled "Only remind on likely run days" in Settings.
        if (settings.useRunPredictor && !healthConnect.isLikelyRunDay()) {
            Log.d(TAG, "Predictor says rest day — suppressing reminder")
            return "suppressed: predictor says rest day"
        }

        // Fetch weather to personalise the nudge.
        val loc = lastKnownLocation()
        var defaultReason: String? = null
        val (title, body) = if (loc != null) {
            val serverUrl = settings.serverUrl.trimEnd('/')
            val url = "$serverUrl/api/brief/run?lat=${loc.first}&lon=${loc.second}"
            fetchWeatherNudge(url)
                ?: defaultNudge().also { defaultReason = "server unreachable" }
        } else {
            defaultNudge().also { defaultReason = "no location" }
        }

        NotificationHelper.postRunReminder(applicationContext, title, body)
        Log.d(TAG, "Reminder posted (defaultReason=$defaultReason)")
        return defaultReason?.let { "fired (default content — $it)" } ?: "fired"
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
