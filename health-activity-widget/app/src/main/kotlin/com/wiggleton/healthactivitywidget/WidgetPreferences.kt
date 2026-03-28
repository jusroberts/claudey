package com.wiggleton.healthactivitywidget

import android.content.Context
import android.graphics.Color
import androidx.core.content.edit
import java.time.LocalDate
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
     * Returns the color for [activityKey], auto-assigning one on first access.
     *
     * Steps always get slot 0 (green). Each new exercise type claims the next
     * available slot (1, 2, 3 …), cycling back to 1 once all slots are used.
     * This guarantees distinct default colors for up to 7 concurrent exercise types.
     * Once assigned, the color is persisted and won't change unless the user picks
     * a different one in the config screen.
     */
    fun getActivityColor(activityKey: String): Int {
        val stored = prefs.getInt("color_$activityKey", COLOR_NOT_SET)
        if (stored != COLOR_NOT_SET) return stored

        val index = if (activityKey == STEPS_KEY) {
            0
        } else {
            val next = prefs.getInt(KEY_NEXT_COLOR_INDEX, 1)
            prefs.edit { putInt(KEY_NEXT_COLOR_INDEX, if (next >= PRESET_COLORS.lastIndex) 1 else next + 1) }
            next
        }

        val color = PRESET_COLORS[index]
        prefs.edit { putInt("color_$activityKey", color) }
        return color
    }

    fun setActivityColor(activityKey: String, color: Int) =
        prefs.edit { putInt("color_$activityKey", color) }

    /**
     * Persists [data] so the widget can render immediately on the next wake before
     * Health Connect responds. Serialised as "YYYY-MM-DD|key1,key2;..." in a single pref.
     */
    fun saveActivityCache(data: Map<LocalDate, Set<String>>) {
        val encoded = data.entries.joinToString(";") { (date, keys) ->
            "$date|${keys.joinToString(",")}"
        }
        prefs.edit { putString(KEY_ACTIVITY_CACHE, encoded) }
    }

    /** Returns the last cached activity data, or an empty map if nothing is stored yet. */
    fun loadActivityCache(): Map<LocalDate, Set<String>> {
        val raw = prefs.getString(KEY_ACTIVITY_CACHE, "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val date = try { LocalDate.parse(parts[0]) } catch (_: Exception) { return@mapNotNull null }
            val keys = parts[1].split(",").filter { it.isNotEmpty() }.toSet()
            date to keys
        }.toMap()
    }

    companion object {
        private const val PREFS_NAME = "widget_prefs"
        private const val KEY_SHOW_STEPS = "show_steps"
        private const val KEY_DISABLED_EXERCISE_TYPES = "disabled_exercise_types"
        private const val KEY_ACTIVITY_CACHE = "activity_cache"
        private const val KEY_NEXT_COLOR_INDEX = "next_color_index"
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
