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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val TAG = "CommuteWorker"

private val COMMUTE_DAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
)

// Toronto bounding box (approximate) for afternoon location check
private const val TORONTO_LAT_MIN = 43.58
private const val TORONTO_LAT_MAX = 43.85
private const val TORONTO_LON_MIN = -79.65
private const val TORONTO_LON_MAX = -79.10

class CommuteWorker(context: Context, params: WorkerParameters) :
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
        val outcome = runCatching { postBrief() }.getOrElse { e ->
            Log.e(TAG, "Commute brief failed", e)
            "error: ${e.message?.take(120) ?: e.javaClass.simpleName}"
        }
        Log.d(TAG, "Outcome: $outcome")
        runCatching {
            WorkerOutcomeStore.record(applicationContext, WorkerOutcomeStore.KEY_COMMUTE, outcome)
        }.onFailure { Log.e(TAG, "Failed to record outcome", it) }
        return Result.success()
    }

    /** Runs the brief logic and returns the outcome string to record. */
    private suspend fun postBrief(): String {
        val isAfternoon = inputData.getBoolean("is_afternoon", false)
        val today = LocalDate.now().dayOfWeek

        if (isAfternoon) {
            if (today !in WEEKDAYS) {
                Log.d(TAG, "Not a weekday — skipping afternoon commute")
                return "skipped: not a weekday"
            }
        } else {
            if (today !in COMMUTE_DAYS) {
                Log.d(TAG, "Not a commute day — skipping")
                return "skipped: not a commute day"
            }
        }

        val loc = lastKnownLocation() ?: run {
            Log.w(TAG, "No location — skipping")
            return "error: no location available"
        }

        if (isAfternoon) {
            val (lat, lon) = loc
            if (lat !in TORONTO_LAT_MIN..TORONTO_LAT_MAX || lon !in TORONTO_LON_MIN..TORONTO_LON_MAX) {
                Log.d(TAG, "Not in Toronto ($lat, $lon) — skipping afternoon commute")
                return "skipped: not in Toronto"
            }
        }

        val isRunDay = if (isAfternoon) false else healthConnect.isLikelyRunDay()
        val direction = if (isAfternoon) "inbound" else "outbound"

        Log.d(TAG, "isAfternoon=$isAfternoon isRunDay=$isRunDay loc=${loc.first},${loc.second}")

        val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
        val url = "$serverUrl/api/brief/commute" +
            "?lat=${loc.first}&lon=${loc.second}&is_run_day=$isRunDay&direction=$direction"

        val fetched = runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute()
                .use { it.body?.string() ?: "" }

            val obj = json.parseToJsonElement(body).jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: error("server response missing title")
            val briefBody = obj["body"]?.jsonPrimitive?.content ?: ""
            title to briefBody
        }.getOrElse { e ->
            Log.e(TAG, "Failed to fetch commute brief — posting default", e)
            null
        }

        return if (fetched != null) {
            NotificationHelper.postCommuteBrief(applicationContext, fetched.first, fetched.second)
            Log.d(TAG, "Posted: ${fetched.first}")
            "fired"
        } else {
            NotificationHelper.postCommuteBrief(
                applicationContext,
                "🚆 Commute brief",
                "Couldn't reach the server for commute conditions — check GO transit before leaving.",
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
