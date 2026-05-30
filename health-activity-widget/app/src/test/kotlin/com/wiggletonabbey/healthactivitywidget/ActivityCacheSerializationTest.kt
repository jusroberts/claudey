package com.wiggletonabbey.healthactivitywidget

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ActivityCacheSerializationTest {

    private lateinit var prefs: WidgetPreferences

    @Before
    fun setUp() {
        prefs = WidgetPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `empty map round-trips to empty map`() {
        prefs.saveActivityCache(emptyMap())
        assertEquals(emptyMap<LocalDate, Set<String>>(), prefs.loadActivityCache())
    }

    @Test
    fun `single date with one key round-trips`() {
        val data = mapOf(LocalDate.of(2024, 6, 15) to setOf("steps"))
        prefs.saveActivityCache(data)
        assertEquals(data, prefs.loadActivityCache())
    }

    @Test
    fun `single date with multiple keys round-trips`() {
        val data = mapOf(LocalDate.of(2024, 6, 15) to setOf("steps", "exercise_56", "exercise_79"))
        prefs.saveActivityCache(data)
        assertEquals(data, prefs.loadActivityCache())
    }

    @Test
    fun `multiple dates round-trip`() {
        val data = mapOf(
            LocalDate.of(2024, 6, 14) to setOf("steps"),
            LocalDate.of(2024, 6, 15) to setOf("exercise_56"),
            LocalDate.of(2024, 6, 16) to setOf("steps", "exercise_79"),
        )
        prefs.saveActivityCache(data)
        assertEquals(data, prefs.loadActivityCache())
    }

    @Test
    fun `dates at year boundary round-trip`() {
        val data = mapOf(
            LocalDate.of(2024, 12, 31) to setOf("steps"),
            LocalDate.of(2025, 1, 1) to setOf("exercise_56"),
        )
        prefs.saveActivityCache(data)
        assertEquals(data, prefs.loadActivityCache())
    }

    @Test
    fun `malformed stored string returns partial results without crashing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sharedPrefs = context.getSharedPreferences("widget_prefs", android.content.Context.MODE_PRIVATE)
        // One valid entry mixed with one corrupt entry (missing pipe separator)
        sharedPrefs.edit().putString("activity_cache", "2024-06-15|steps;NOTADATE_NODIVIDER;2024-06-16|exercise_56").apply()

        val result = prefs.loadActivityCache()

        assertEquals(2, result.size)
        assertEquals(setOf("steps"), result[LocalDate.of(2024, 6, 15)])
        assertEquals(setOf("exercise_56"), result[LocalDate.of(2024, 6, 16)])
    }

    @Test
    fun `entry with pipe in wrong position is skipped gracefully`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sharedPrefs = context.getSharedPreferences("widget_prefs", android.content.Context.MODE_PRIVATE)
        // Entry where the date portion is invalid
        sharedPrefs.edit().putString("activity_cache", "not-a-date|steps;2024-06-15|exercise_56").apply()

        val result = prefs.loadActivityCache()

        assertEquals(1, result.size)
        assertEquals(setOf("exercise_56"), result[LocalDate.of(2024, 6, 15)])
    }
}
