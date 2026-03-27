package com.example.healthactivitywidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Transparent activity that requests Health Connect permissions and then updates
 * all widget instances before finishing.
 *
 * Launched when the user taps a widget that does not yet have the required permissions.
 */
class PermissionActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ ->
        // Refresh all widgets regardless of whether every permission was granted;
        // widgets with partial/no permissions will show an empty grid and remain tappable.
        val manager = AppWidgetManager.getInstance(this)
        val component = ComponentName(this, HealthWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        CoroutineScope(Dispatchers.IO).launch {
            ids.forEach { id -> HealthWidgetProvider.updateWidget(this@PermissionActivity, manager, id) }
            // Finish on main thread after updates are dispatched
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            // Health Connect is not installed on this device; nothing to request
            finish()
            return
        }

        requestPermissions.launch(HealthConnectRepository.REQUIRED_PERMISSIONS)
    }
}
