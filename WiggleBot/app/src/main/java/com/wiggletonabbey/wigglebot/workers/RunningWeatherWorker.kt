package com.wiggletonabbey.wigglebot.workers

import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wiggletonabbey.wigglebot.notifications.EXTRA_INVENTORY_ID
import com.wiggletonabbey.wigglebot.notifications.EXTRA_PARK_NAME
import com.wiggletonabbey.wigglebot.notifications.NotificationHelper
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
        // Commute worker fires at 5:30am on Mon/Thu and already covers running — skip.
        if (LocalDate.now().dayOfWeek in COMMUTE_DAYS) {
            Log.d(TAG, "Commute day — deferring to CommuteWorker")
            return Result.success()
        }

        val loc = lastKnownLocation() ?: run {
            Log.w(TAG, "No location available")
            return Result.success()
        }

        val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
        val url = "$serverUrl/api/brief/run?lat=${loc.first}&lon=${loc.second}"

        val today = LocalDate.now().dayOfWeek
        val isWeekend = today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY

        return runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute()
                .use { it.body?.string() ?: "" }

            val obj = json.parseToJsonElement(body).jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return Result.success()
            val briefBody = obj["body"]?.jsonPrimitive?.content ?: ""

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

            NotificationHelper.postRunBrief(applicationContext, title, briefBody, actions)
            Log.d(TAG, "Posted: $title (weekend=$isWeekend)")
            Result.success()
        }.getOrElse { e ->
            Log.e(TAG, "Failed to fetch run brief", e)
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
