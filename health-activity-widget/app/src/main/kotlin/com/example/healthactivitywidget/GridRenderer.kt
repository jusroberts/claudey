package com.example.healthactivitywidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate

/**
 * Renders an activity grid bitmap in the style of a GitHub Contribution Calendar.
 *
 * Layout:
 *   - Rows (0–6) represent days of the week, Sunday at the top.
 *   - Columns (0 … weeks-1) represent weeks, oldest on the left.
 *   - Cells are square with a small gap between them.
 *   - Active days are drawn in [ACTIVE_COLOR]; past inactive days in [INACTIVE_COLOR].
 *   - Future cells (beyond today) are left transparent.
 */
object GridRenderer {

    private const val ROWS = 7

    private val BACKGROUND_COLOR = Color.parseColor("#0D1117")
    private val INACTIVE_COLOR   = Color.parseColor("#161B22")
    private val ACTIVE_COLOR     = Color.parseColor("#39D353")

    fun render(
        activeDays: Set<LocalDate>,
        weeks: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Bitmap {
        val today = LocalDate.now()

        // Align to Sunday of the current week so columns are always full weeks
        val todayDow = today.dayOfWeek.value % 7 // Sunday=0
        val currentWeekSunday = today.minusDays(todayDow.toLong())
        val startDate = currentWeekSunday.minusWeeks((weeks - 1).toLong())

        // Gap is ~10 % of the smaller cell dimension, at least 1 px
        val rawCellW = bitmapWidth.toFloat() / weeks
        val rawCellH = bitmapHeight.toFloat() / ROWS
        val gap = maxOf(1, (minOf(rawCellW, rawCellH) * 0.12f).toInt())

        val cellW = (bitmapWidth  - (weeks - 1) * gap) / weeks
        val cellH = (bitmapHeight - (ROWS  - 1) * gap) / ROWS
        val corner = maxOf(2f, minOf(cellW, cellH) / 5f)

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND_COLOR)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()

        for (col in 0 until weeks) {
            for (row in 0 until ROWS) {
                val date = startDate.plusDays((col * 7L + row))
                if (date > today) continue // don't paint future cells

                paint.color = if (activeDays.contains(date)) ACTIVE_COLOR else INACTIVE_COLOR

                val left = col * (cellW + gap)
                val top  = row * (cellH + gap)
                rect.set(left.toFloat(), top.toFloat(), (left + cellW).toFloat(), (top + cellH).toFloat())
                canvas.drawRoundRect(rect, corner, corner, paint)
            }
        }

        return bitmap
    }
}
