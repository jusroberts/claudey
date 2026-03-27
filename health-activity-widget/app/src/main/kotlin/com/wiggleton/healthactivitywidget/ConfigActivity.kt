package com.wiggleton.healthactivitywidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import kotlinx.coroutines.CoroutineScope
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
        val saveButton        = findViewById<Button>(R.id.save_button)

        // Steps row (static — always present)
        stepsContainer.addView(
            buildRow(
                name = "Step Goal (10K/day)",
                activityKey = WidgetPreferences.STEPS_KEY,
                initialEnabled = prefs.showSteps,
                onToggle = { pendingToggles[WidgetPreferences.STEPS_KEY] = it },
            )
        )

        // Exercise rows — loaded asynchronously from Health Connect
        CoroutineScope(Dispatchers.IO).launch {
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

        saveButton.setOnClickListener {
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

            // Refresh all widget instances
            val manager   = AppWidgetManager.getInstance(this)
            val component = ComponentName(this, HealthWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { id ->
                CoroutineScope(Dispatchers.IO).launch {
                    HealthWidgetProvider.updateWidget(this@ConfigActivity, manager, id)
                }
            }
            finish()
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
            val cur   = v.tag as Int
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
