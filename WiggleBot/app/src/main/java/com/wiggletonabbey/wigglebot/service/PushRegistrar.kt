package com.wiggletonabbey.wigglebot.service

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Registers this device's FCM token with the server (POST /api/devices) so
 * server-initiated pushes (finance reports, run-reminder wakes) can reach it.
 *
 * Safe to call when Firebase isn't configured (no google-services.json at
 * build time): it logs and returns instead of crashing.
 */
object PushRegistrar {

    private const val TAG = "PushRegistrar"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun registerIfPossible(context: Context) {
        val token = fetchToken(context) ?: return
        registerToken(context, token)
    }

    suspend fun registerToken(context: Context, token: String) {
        val serverUrl = SettingsRepository(context).settings.first().serverUrl.trimEnd('/')
        val body = JSONObject()
            .put("token", token)
            .put("platform", "android")
            .toString()

        runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(
                    Request.Builder()
                        .url("$serverUrl/api/devices")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    Log.d(TAG, "Token registration: HTTP ${resp.code}")
                }
            }
        }.onFailure { Log.w(TAG, "Token registration failed: ${it.message}") }
    }

    private suspend fun fetchToken(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.i(TAG, "Firebase not configured (no google-services.json) — push disabled")
            return null
        }

        return runCatching {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        cont.resume(task.result)
                    } else {
                        Log.w(TAG, "FCM token fetch failed", task.exception)
                        cont.resume(null)
                    }
                }
            }
        }.getOrNull()
    }
}
