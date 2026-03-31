package com.wiggletonabbey.healthactivitywidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigActivity : ComponentActivity() {

    private lateinit var prefs: WidgetPreferences

    // Pending changes — written to prefs on Save
    private val pendingColors   = mutableMapOf<String, Int>()   // activityKey → chosen color
    private val pendingToggles  = mutableMapOf<String, Boolean>() // activityKey → enabled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        prefs = WidgetPreferences(this)

        val stepsContainer    = findViewById<LinearLayout>(R.id.steps_container)
        val exerciseLoading   = findViewById<ProgressBar>(R.id.exercise_loading)
        val exerciseContainer = findViewById<LinearLayout>(R.id.exercise_container)
        val noExerciseLabel   = findViewById<TextView>(R.id.no_exercise_label)
        val historyWarning      = findViewById<TextView>(R.id.history_permission_warning)
        val backgroundValue     = findViewById<TextView>(R.id.background_value)
        val refreshButton       = findViewById<Button>(R.id.refresh_button)
        val saveButton          = findViewById<Button>(R.id.save_button)

        // Steps row (static — always present)
        stepsContainer.addView(
            buildRow(
                name = "Step Goal (10K/day)",
                activityKey = WidgetPreferences.STEPS_KEY,
                initialEnabled = prefs.showSteps,
                onToggle = { pendingToggles[WidgetPreferences.STEPS_KEY] = it },
            )
        )

        // Background style — cycles through Transparent → Dark → Light on tap
        var pendingBackground = prefs.backgroundStyle
        fun backgroundLabel(style: Int) = when (style) {
            WidgetPreferences.BACKGROUND_DARK  -> "Dark"
            WidgetPreferences.BACKGROUND_LIGHT -> "Light"
            else                               -> "None"
        }
        backgroundValue.text = backgroundLabel(pendingBackground)
        findViewById<View>(R.id.background_row).setOnClickListener {
            pendingBackground = (pendingBackground + 1) % 3
            backgroundValue.text = backgroundLabel(pendingBackground)
        }

        historyWarning.setOnClickListener {
            startActivity(
                Intent(this@ConfigActivity, PermissionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }

        // Exercise rows — loaded asynchronously from Health Connect
        lifecycleScope.launch(Dispatchers.IO) {
            val types = HealthConnectRepository(this@ConfigActivity)
                .getExerciseTypes(HealthWidgetProvider.WEEKS)

            withContext(Dispatchers.Main) {
                exerciseLoading.visibility = View.GONE
                if (types.isEmpty()) {
                    noExerciseLabel.visibility = View.VISIBLE
                } else {
                    val disabled = prefs.disabledExerciseTypes
                    types.forEach { (typeId, typeName) ->
                        val key = WidgetPreferences.exerciseKey(typeId)
                        exerciseContainer.addView(
                            buildRow(
                                name = typeName,
                                activityKey = key,
                                initialEnabled = typeId !in disabled,
                                onToggle = { pendingToggles[key] = it },
                            )
                        )
                    }
                }
            }
        }

        refreshButton.setOnClickListener {
            refreshButton.isEnabled = false
            refreshButton.text = "Refreshing…"
            val manager   = AppWidgetManager.getInstance(this)
            val component = ComponentName(this, HealthWidgetProvider::class.java)
            lifecycleScope.launch(Dispatchers.IO) {
                manager.getAppWidgetIds(component).forEach { id ->
                    HealthWidgetProvider.updateWidget(this@ConfigActivity, manager, id)
                }
                withContext(Dispatchers.Main) {
                    refreshButton.text = "Refresh Now"
                    refreshButton.isEnabled = true
                }
            }
        }

        // Re-checked in onResume so it disappears after returning from PermissionActivity
        checkHistoryPermission(historyWarning)

        saveButton.setOnClickListener {
            // Persist background style
            prefs.backgroundStyle = pendingBackground

            // Persist color changes
            pendingColors.forEach { (key, color) -> prefs.setActivityColor(key, color) }

            // Persist toggle changes
            prefs.showSteps = pendingToggles[WidgetPreferences.STEPS_KEY] ?: prefs.showSteps
            val currentDisabled = prefs.disabledExerciseTypes.toMutableSet()
            pendingToggles.forEach { (key, enabled) ->
                if (key == WidgetPreferences.STEPS_KEY) return@forEach
                val typeId = key.removePrefix("exercise_").toIntOrNull() ?: return@forEach
                if (enabled) currentDisabled.remove(typeId) else currentDisabled.add(typeId)
            }
            prefs.disabledExerciseTypes = currentDisabled

            // Refresh all widget instances, then close
            val manager   = AppWidgetManager.getInstance(this)
            val component = ComponentName(this, HealthWidgetProvider::class.java)
            lifecycleScope.launch(Dispatchers.IO) {
                manager.getAppWidgetIds(component).forEach { id ->
                    HealthWidgetProvider.updateWidget(this@ConfigActivity, manager, id)
                }
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkHistoryPermission(findViewById(R.id.history_permission_warning))
    }

    private fun checkHistoryPermission(warning: TextView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val hasHistory = HealthConnectRepository(this@ConfigActivity).hasHistoryPermission()
            withContext(Dispatchers.Main) {
                warning.visibility = if (hasHistory) View.GONE else View.VISIBLE
            }
        }
    }

    /**
     * Builds one activity row: [Name ··········] [color circle] [Switch]
     * Tapping the color circle cycles to the next preset color.
     */
    private fun buildRow(
        name: String,
        activityKey: String,
        initialEnabled: Boolean,
        onToggle: (Boolean) -> Unit,
    ): View {
        val density  = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (14 * density).toInt() }
        }

        val label = TextView(this).apply {
            text = name
            textSize = 15f
            setTextColor(0xFFE6EDF3.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val colorCircle = buildColorCircle(activityKey, density)

        // MaterialSwitch requires the Material Components library which is not otherwise a dependency.
        @Suppress("DEPRECATION")
        val switch = Switch(this).apply {
            isChecked = initialEnabled
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }

        row.addView(label)
        row.addView(colorCircle)
        row.addView(switch)
        return row
    }

    private fun buildColorCircle(activityKey: String, density: Float): View {
        val sizePx   = (32 * density).toInt()
        val marginPx = (10 * density).toInt()

        val view = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                setMargins(0, 0, marginPx, 0)
            }
        }

        // Track current color in the view's tag so the cycle logic is self-contained
        view.tag = pendingColors[activityKey] ?: prefs.getActivityColor(activityKey)
        applyCircle(view)

        view.setOnClickListener { v ->
            val cur   = v.tag as? Int ?: return@setOnClickListener
            val idx   = WidgetPreferences.PRESET_COLORS.indexOf(cur)
            val next  = WidgetPreferences.PRESET_COLORS[(idx + 1) % WidgetPreferences.PRESET_COLORS.size]
            v.tag = next
            pendingColors[activityKey] = next
            applyCircle(v)
        }

        return view
    }

    private fun applyCircle(view: View) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(view.tag as Int)
        }
    }
}
