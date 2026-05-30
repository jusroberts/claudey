package com.wiggletonabbey.wigglebot.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot completed — rescheduling alarms")
        AlarmScheduler.schedule(context)
    }
}
