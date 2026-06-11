package com.wiggletonabbey.wigglebot.workers

import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wiggletonabbey.wigglebot.notifications.EXTRA_INVENTORY_ID
import com.wiggletonabbey.wigglebot.notifications.EXTRA_PARK_NAME
import com.wiggletonabbey.wigglebot.notifications.NotificationHelper
import com.wiggletonabbey.wigglebot.schedule.WorkerOutcomeStore
import com.wiggletonabbey.wigglebot.service.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val INVENTORY_HILTON_FALLS = 1118597880L
private const val INVENTORY_RATTLESNAKE  = 1811599148L

private const val TAG = "RunningWeatherWorker"
private val COMMUTE_DAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)

class RunningWeatherWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val settingsRepo = SettingsRepository(applicationContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        // Always Result.success() so WorkManager doesn't retry-spam; the outcome
        // (including errors) is recorded for the Settings diagnostics panel.
        val outcome = runCatching { postBrief() }.getOrElse { e ->
            Log.e(TAG, "Run brief failed", e)
            "error: ${e.message?.take(120) ?: e.javaClass.simpleName}"
        }
        Log.d(TAG, "Outcome: $outcome")
        runCatching {
            WorkerOutcomeStore.record(applicationContext, WorkerOutcomeStore.KEY_RUN_BRIEF, outcome)
        }.onFailure { Log.e(TAG, "Failed to record outcome", it) }
        return Result.success()
    }

    /** Runs the brief logic and returns the outcome string to record. */
    private suspend fun postBrief(): String {
        // Commute worker fires at 5:30am on Mon/Thu and already covers running — skip.
        if (LocalDate.now().dayOfWeek in COMMUTE_DAYS) {
            Log.d(TAG, "Commute day — deferring to CommuteWorker")
            return "skipped: commute day — CommuteWorker covers it"
        }

        val loc = lastKnownLocation() ?: run {
            Log.w(TAG, "No location available")
            return "error: no location available"
        }

        val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
        val url = "$serverUrl/api/brief/run?lat=${loc.first}&lon=${loc.second}"

        val today = LocalDate.now().dayOfWeek
        val isWeekend = today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY

        val actions = if (isWeekend) {
            listOf(
                NotificationHelper.parkBookingAction(
                    applicationContext, "Book Hilton Falls",
                    INVENTORY_HILTON_FALLS, "Hilton Falls", 2001
                ),
                NotificationHelper.parkBookingAction(
                    applicationContext, "Book Rattlesnake",
                    INVENTORY_RATTLESNAKE, "Rattlesnake Point", 2002
                ),
            )
        } else emptyList()

        val fetched = runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute()
                .use { it.body?.string() ?: "" }

            val obj = json.parseToJsonElement(body).jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: error("server response missing title")
            val briefBody = obj["body"]?.jsonPrimitive?.content ?: ""
            title to briefBody
        }.getOrElse { e ->
            Log.e(TAG, "Failed to fetch run brief — posting default", e)
            null
        }

        return if (fetched != null) {
            NotificationHelper.postRunBrief(applicationContext, fetched.first, fetched.second, actions)
            Log.d(TAG, "Posted: ${fetched.first} (weekend=$isWeekend)")
            "fired"
        } else {
            NotificationHelper.postRunBrief(
                applicationContext,
                "🏃 Morning run brief",
                "Couldn't reach the server for running conditions — check the weather before heading out.",
                actions,
            )
            "fired (default content — server unreachable)"
        }
    }

    private fun lastKnownLocation(): Pair<Double, Double>? {
        val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstNotNullOfOrNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }
            ?.let { it.latitude to it.longitude }
    }
}
