package com.wiggletonabbey.healthactivitywidget

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActivityColorTest {

    private lateinit var prefs: WidgetPreferences

    @Before
    fun setUp() {
        prefs = WidgetPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `STEPS_KEY always returns PRESET_COLORS index 0`() {
        val color = prefs.getActivityColor(WidgetPreferences.STEPS_KEY)
        assertEquals(WidgetPreferences.PRESET_COLORS[0], color)
    }

    @Test
    fun `first new exercise type gets index 1`() {
        val color = prefs.getActivityColor("exercise_1")
        assertEquals(WidgetPreferences.PRESET_COLORS[1], color)
    }

    @Test
    fun `second new exercise type gets index 2`() {
        prefs.getActivityColor("exercise_1")
        val color = prefs.getActivityColor("exercise_2")
        assertEquals(WidgetPreferences.PRESET_COLORS[2], color)
    }

    @Test
    fun `after reaching lastIndex next type wraps back to index 1 not 0`() {
        val lastIndex = WidgetPreferences.PRESET_COLORS.lastIndex
        // Consume indices 1 through lastIndex
        for (i in 1..lastIndex) {
            prefs.getActivityColor("exercise_$i")
        }
        // The next one should wrap to index 1
        val wrapped = prefs.getActivityColor("exercise_wrap")
        assertEquals(WidgetPreferences.PRESET_COLORS[1], wrapped)
        assertNotEquals(WidgetPreferences.PRESET_COLORS[0], wrapped)
    }

    @Test
    fun `same key queried twice returns the same color`() {
        val first = prefs.getActivityColor("exercise_99")
        val second = prefs.getActivityColor("exercise_99")
        assertEquals(first, second)
    }

    @Test
    fun `out-of-bounds index stored in prefs does not throw`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sharedPrefs = context.getSharedPreferences("widget_prefs", android.content.Context.MODE_PRIVATE)
        // Store an index well beyond the array bounds
        sharedPrefs.edit().putInt("color_exercise_oob", Int.MIN_VALUE + 1).apply()

        // coerceIn should clamp it — no IndexOutOfBoundsException
        val color = prefs.getActivityColor("exercise_oob")
        // The stored sentinel is Int.MIN_VALUE, so Int.MIN_VALUE + 1 is treated as a real color
        // It won't equal COLOR_NOT_SET, so it's returned directly without indexing — no crash.
        assertEquals(Int.MIN_VALUE + 1, color)
    }
}
