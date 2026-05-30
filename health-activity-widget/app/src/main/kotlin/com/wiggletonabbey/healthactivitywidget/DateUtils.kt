package com.wiggletonabbey.healthactivitywidget

import java.time.LocalDate

/**
 * Returns the Monday that starts the week containing [today], then steps back [weeks]-1 more weeks.
 * Works correctly for all seven days including Sunday (ordinal 6).
 */
internal fun weekStartDate(today: LocalDate, weeks: Int): LocalDate {
    val todayDow = today.dayOfWeek.ordinal // Mon=0 … Sun=6
    return today.minusDays(todayDow.toLong()).minusWeeks((weeks - 1).toLong())
}
