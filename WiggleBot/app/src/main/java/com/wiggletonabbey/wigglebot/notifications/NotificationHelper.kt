package com.wiggletonabbey.wigglebot.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wiggletonabbey.wigglebot.MainActivity

object NotificationHelper {

    const val CHANNEL_RUNNING = "wigglebot_running"
    const val CHANNEL_COMMUTE = "wigglebot_commute"
    const val CHANNEL_REMINDER = "wigglebot_reminder"

    private const val ID_RUNNING  = 1001
    private const val ID_COMMUTE  = 1002
    private const val ID_REMINDER = 1003
    private const val ID_PARK     = 1004

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RUNNING, "Running Weather", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        description = "Daily 6am running conditions brief"
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    },
                NotificationChannel(CHANNEL_COMMUTE, "Commute Updates", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        description = "Mon/Thu commute conditions and GO alerts"
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    },
                NotificationChannel(CHANNEL_REMINDER, "Run Reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "Evening nudge when you haven't run yet"
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    },
            )
        )
    }

    fun postRunBrief(
        context: Context,
        title: String,
        body: String,
        actions: List<NotificationCompat.Action> = emptyList(),
    ) = post(context, CHANNEL_RUNNING, ID_RUNNING, title, body, actions)

    fun postCommuteBrief(context: Context, title: String, body: String) =
        post(context, CHANNEL_COMMUTE, ID_COMMUTE, title, body)

    fun postRunReminder(context: Context, title: String, body: String) =
        post(context, CHANNEL_REMINDER, ID_REMINDER, title, body)

    fun postParkBookingResult(context: Context, parkName: String, success: Boolean, message: String) {
        val title = if (success) "✅ $parkName booked!" else "❌ $parkName booking failed"
        post(context, CHANNEL_RUNNING, ID_PARK, title, message)
    }

    fun parkBookingAction(
        context: Context,
        label: String,
        inventoryId: Long,
        parkName: String,
        requestCode: Int,
    ): NotificationCompat.Action {
        val intent = Intent(ACTION_BOOK_PARK).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_INVENTORY_ID, inventoryId)
            putExtra(EXTRA_PARK_NAME, parkName)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(0, label, pi)
    }

    private fun post(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        body: String,
        actions: List<NotificationCompat.Action> = emptyList(),
    ) {
        val tapIntent = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        actions.forEach { builder.addAction(it) }

        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
