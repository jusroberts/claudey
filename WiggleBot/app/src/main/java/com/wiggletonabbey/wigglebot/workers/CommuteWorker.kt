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
        val isAfternoon = inputData.getBoolean("is_afternoon", false)
        val today = LocalDate.now().dayOfWeek

        if (isAfternoon) {
            if (today !in WEEKDAYS) {
                Log.d(TAG, "Not a weekday — skipping afternoon commute")
                return Result.success()
            }
        } else {
            if (today !in COMMUTE_DAYS) {
                Log.d(TAG, "Not a commute day — skipping")
                return Result.success()
            }
        }

        val loc = lastKnownLocation() ?: run {
            Log.w(TAG, "No location — skipping")
            return Result.success()
        }

        if (isAfternoon) {
            val (lat, lon) = loc
            if (lat !in TORONTO_LAT_MIN..TORONTO_LAT_MAX || lon !in TORONTO_LON_MIN..TORONTO_LON_MAX) {
                Log.d(TAG, "Not in Toronto ($lat, $lon) — skipping afternoon commute")
                return Result.success()
            }
        }

        val isRunDay = if (isAfternoon) false else healthConnect.isLikelyRunDay()
        val direction = if (isAfternoon) "inbound" else "outbound"

        Log.d(TAG, "isAfternoon=$isAfternoon isRunDay=$isRunDay loc=${loc.first},${loc.second}")

        val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
        val url = "$serverUrl/api/brief/commute" +
            "?lat=${loc.first}&lon=${loc.second}&is_run_day=$isRunDay&direction=$direction"

        return runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute()
                .use { it.body?.string() ?: "" }

            val obj = json.parseToJsonElement(body).jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return Result.success()
            val briefBody = obj["body"]?.jsonPrimitive?.content ?: ""

            NotificationHelper.postCommuteBrief(applicationContext, title, briefBody)
            Log.d(TAG, "Posted: $title")
            Result.success()
        }.getOrElse { e ->
            Log.e(TAG, "Failed to fetch commute brief", e)
            Result.success()
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
