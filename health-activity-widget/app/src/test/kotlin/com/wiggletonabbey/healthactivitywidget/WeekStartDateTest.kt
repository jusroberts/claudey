package com.wiggletonabbey.healthactivitywidget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@RunWith(Parameterized::class)
class WeekStartDateTest(private val dayOfWeek: DayOfWeek) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun days() = DayOfWeek.entries.toList()
    }

    @Test
    fun `weekStartDate returns the Monday of the current week for any day`() {
        // Pick a known Monday and offset by the day under test
        val monday = LocalDate.of(2024, 1, 1) // Known Monday
        val today = monday.with(TemporalAdjusters.nextOrSame(dayOfWeek))
        val expectedMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        val result = weekStartDate(today, weeks = 1)

        assertEquals("weekStartDate for $dayOfWeek should be its week's Monday", expectedMonday, result)
    }

    @Test
    fun `weekStartDate window spans exactly weeks × 7 days for any day`() {
        val monday = LocalDate.of(2024, 1, 1)
        val today = monday.with(TemporalAdjusters.nextOrSame(dayOfWeek))
        val weeks = 13

        val start = weekStartDate(today, weeks)
        val end = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

        val span = end.toEpochDay() - start.toEpochDay() + 1
        assertEquals("Window should span exactly ${weeks * 7} days for $dayOfWeek", (weeks * 7).toLong(), span)
    }
}
