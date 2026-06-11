package com.wiggletonabbey.wigglebot.service

import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wiggletonabbey.wigglebot.notifications.NotificationHelper
import com.wiggletonabbey.wigglebot.workers.CommuteWorker
import com.wiggletonabbey.wigglebot.workers.RunReminderWorker
import com.wiggletonabbey.wigglebot.workers.RunningWeatherWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives data-only FCM messages from the wigglebot server:
 *   {"type": "notify", "title": ..., "body": ..., "channel": <channel id>}
 *     → show a notification
 *   {"type": "wake", "worker": "run_reminder" | "run_brief" | "commute"}
 *     → run the matching worker now (server-driven reminders that don't
 *       depend on local alarms surviving Doze)
 */
class WigglebotFcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token")
        scope.launch { PushRegistrar.registerToken(applicationContext, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.d(TAG, "Push received: type=${data["type"]} worker=${data["worker"]}")

        when (data["type"]) {
            "notify" -> NotificationHelper.postServerPush(
                applicationContext,
                channelId = data["channel"],
                title = data["title"] ?: "WiggleBot",
                body = data["body"] ?: "",
            )

            "wake" -> enqueueWorker(data["worker"])

            else -> Log.w(TAG, "Unknown push type: ${data["type"]}")
        }
    }

    private fun enqueueWorker(worker: String?) {
        // No setExpedited(): on API < 31 expedited work requires
        // getForegroundInfo() which these workers don't implement, and the
        // high-priority FCM window already lets plain work run immediately.
        val request = when (worker) {
            "run_reminder" -> OneTimeWorkRequestBuilder<RunReminderWorker>()
            "run_brief" -> OneTimeWorkRequestBuilder<RunningWeatherWorker>()
            "commute" -> OneTimeWorkRequestBuilder<CommuteWorker>()
            else -> {
                Log.w(TAG, "Unknown wake worker: $worker")
                return
            }
        }.build()

        WorkManager.getInstance(applicationContext).enqueue(request)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "WigglebotFcm"
    }
}
