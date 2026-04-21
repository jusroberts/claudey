package com.wiggletonabbey.wigglebot.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.wiggletonabbey.wigglebot.workers.KEY_PARK_INVENTORY_ID
import com.wiggletonabbey.wigglebot.workers.KEY_PARK_NAME
import com.wiggletonabbey.wigglebot.workers.ParkBookingWorker

const val ACTION_BOOK_PARK = "com.wiggletonabbey.wigglebot.ACTION_BOOK_PARK"
const val EXTRA_INVENTORY_ID = "inventory_id"
const val EXTRA_PARK_NAME = "park_name"

class ParkBookingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BOOK_PARK) return
        val inventoryId = intent.getLongExtra(EXTRA_INVENTORY_ID, -1)
        val parkName = intent.getStringExtra(EXTRA_PARK_NAME) ?: "Park"
        if (inventoryId < 0) return

        val data = Data.Builder()
            .putLong(KEY_PARK_INVENTORY_ID, inventoryId)
            .putString(KEY_PARK_NAME, parkName)
            .build()

        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequestBuilder<ParkBookingWorker>().setInputData(data).build())
    }
}
