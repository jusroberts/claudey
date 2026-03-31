package com.wiggletonabbey.healthactivitywidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.widget.Button
import android.widget.TextView

/**
 * Shown when Health Connect (or the user via Settings) asks the app to explain
 * why it needs health permissions (android.intent.action.VIEW_PERMISSION_USAGE).
 *
 * Must be exported and declare the HEALTH_PERMISSIONS category so Health Connect
 * recognises the app and actually presents the permission dialog.
 */
class RationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rationale)
        findViewById<Button>(R.id.rationale_ok).setOnClickListener { finish() }
    }
}
