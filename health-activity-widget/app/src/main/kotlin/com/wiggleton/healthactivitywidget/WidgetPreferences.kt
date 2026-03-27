package com.wiggleton.healthactivitywidget

import android.content.Context
import android.graphics.Color
import androidx.core.content.edit
import kotlin.math.abs

class WidgetPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var showSteps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_STEPS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_STEPS, value) }

    /** Exercise type IDs the user has explicitly turned off. All others are shown. */
    var disabledExerciseTypes: Set<Int>
        get() {
            val raw = prefs.getString(KEY_DISABLED_EXERCISE_TYPES, "") ?: ""
            return if (raw.isEmpty()) emptySet()
            else raw.split(",").mapNotNull { it.toIntOrNull() }.toSet()
        }
        set(value) = prefs.edit {
            putString(KEY_DISABLED_EXERCISE_TYPES, value.joinToString(","))
        }

    /**
     * Returns the color stored for [activityKey].
     * Defaults: steps → green; exercise types → a stable color derived from the key's hash
     * so each type gets a distinct hue out of the box without any user configuration.
     */
    fun getActivityColor(activityKey: String): Int {
        val stored = prefs.getInt("color_$activityKey", COLOR_NOT_SET)
        if (stored != COLOR_NOT_SET) return stored
        return if (activityKey == STEPS_KEY) PRESET_COLORS[0]
        else PRESET_COLORS[abs(activityKey.hashCode()) % PRESET_COLORS.size]
    }

    fun setActivityColor(activityKey: String, color: Int) =
        prefs.edit { putInt("color_$activityKey", color) }

    companion object {
        private const val PREFS_NAME = "widget_prefs"
        private const val KEY_SHOW_STEPS = "show_steps"
        private const val KEY_DISABLED_EXERCISE_TYPES = "disabled_exercise_types"
        private const val COLOR_NOT_SET = Int.MIN_VALUE

        const val STEPS_KEY = "steps"
        fun exerciseKey(typeId: Int) = "exercise_$typeId"

        val PRESET_COLORS = listOf(
            Color.parseColor("#39D353"), // green
            Color.parseColor("#58A6FF"), // blue
            Color.parseColor("#E3B341"), // yellow
            Color.parseColor("#FF7EB3"), // pink
            Color.parseColor("#BC8CFF"), // purple
            Color.parseColor("#F78166"), // orange-red
            Color.parseColor("#2DD4BF"), // teal
            Color.parseColor("#FFFFFF"), // white
        )
    }
}
