package com.wiggletonabbey.wigglebot.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wiggletonabbey.wigglebot.notifications.NotificationHelper
import com.wiggletonabbey.wigglebot.service.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val TAG = "ParkBookingWorker"
const val KEY_PARK_INVENTORY_ID = "inventory_id"
const val KEY_PARK_NAME = "park_name"

class ParkBookingWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val settingsRepo = SettingsRepository(applicationContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val inventoryId = inputData.getLong(KEY_PARK_INVENTORY_ID, -1)
        val parkName = inputData.getString(KEY_PARK_NAME) ?: "Park"
        if (inventoryId < 0) return Result.failure()

        val settings = settingsRepo.settings.first()
        val today = LocalDate.now().toString()
        val serverUrl = settings.serverUrl.trimEnd('/')

        val payload = "{\"inventory_id\":$inventoryId,\"date\":\"$today\"}"

        return runCatching {
            val body = http.newCall(
                Request.Builder()
                    .url("$serverUrl/api/park/book")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.body?.string() ?: "" }

            val obj = json.parseToJsonElement(body).jsonObject
            val reservationId = obj["reservation"]?.jsonPrimitive?.content
            val error = obj["error"]?.jsonPrimitive?.content

            if (reservationId != null) {
                Log.d(TAG, "Booked $parkName: $reservationId")
                NotificationHelper.postParkBookingResult(
                    applicationContext, parkName,
                    success = true,
                    message = "Reservation $reservationId confirmed for today"
                )
            } else {
                Log.w(TAG, "Booking failed: $error")
                NotificationHelper.postParkBookingResult(
                    applicationContext, parkName,
                    success = false,
                    message = error ?: "Booking failed"
                )
            }
            Result.success()
        }.getOrElse { e ->
            Log.e(TAG, "Park booking error", e)
            NotificationHelper.postParkBookingResult(
                applicationContext, parkName,
                success = false,
                message = "Could not reach server"
            )
            Result.success()
        }
    }
}
