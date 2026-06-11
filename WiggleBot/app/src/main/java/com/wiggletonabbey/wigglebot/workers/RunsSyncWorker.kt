package com.wiggletonabbey.wigglebot.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wiggletonabbey.wigglebot.schedule.HealthConnectHelper
import com.wiggletonabbey.wigglebot.service.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "RunsSyncWorker"

/**
 * Uploads recent Health Connect running sessions (with HR/distance/elevation
 * aggregates) to the server's run store, feeding the coach's training-load
 * model. Runs periodically and on FCM "runs_sync" wakes.
 */
class RunsSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val healthConnect = HealthConnectHelper(context)
    private val settingsRepo = SettingsRepository(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val runs = healthConnect.recentRunsForSync(days = 14)
        if (runs.isEmpty()) {
            Log.d(TAG, "No runs to sync")
            return Result.success()
        }

        val payload = buildJsonObject {
            put("runs", buildJsonArray {
                runs.forEach { r ->
                    add(buildJsonObject {
                        put("external_id", r.externalId)
                        put("started_at", r.startedAt.toString())
                        put("duration_s", r.durationS)
                        r.distanceM?.let { put("distance_m", it) }
                        r.avgHr?.let { put("avg_hr", it) }
                        r.maxHr?.let { put("max_hr", it) }
                        r.elevationGainM?.let { put("elevation_gain_m", it) }
                        r.calories?.let { put("calories", it) }
                        r.title?.let { put("title", it) }
                    })
                }
            })
        }.toString()

        return runCatching {
            val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
            withContext(Dispatchers.IO) {
                http.newCall(
                    Request.Builder()
                        .url("$serverUrl/api/runs/sync")
                        .post(payload.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    Log.d(TAG, "Synced ${runs.size} runs: HTTP ${resp.code}")
                }
            }
            Result.success()
        }.getOrElse { e ->
            Log.w(TAG, "Run sync failed, will retry: ${e.message}")
            Result.retry()
        }
    }
}
