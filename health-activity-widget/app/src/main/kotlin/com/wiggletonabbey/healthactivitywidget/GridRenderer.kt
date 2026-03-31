package com.wiggletonabbey.healthactivitywidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import java.time.LocalDate

/**
 * Renders the activity grid bitmap.
 *
 * Each cell receives a list of up to 3 colors (one per activity type recorded that day).
 *   - 0 colors → inactive (dark cell)
 *   - 1 color  → solid fill
 *   - 2 colors → top half / bottom half
 *   - 3 colors → equal horizontal thirds
 *
 * The rounded corner shape is preserved for multi-color cells by clipping to a Path.
 */
object GridRenderer {

    private const val ROWS = 7

    private val DARK_BACKGROUND  = Color.parseColor("#0D1117")
    private val DARK_INACTIVE    = Color.parseColor("#161B22")
    private val LIGHT_BACKGROUND = Color.parseColor("#F6F8FA")
    private val LIGHT_INACTIVE   = Color.parseColor("#EAEEF2")

    fun render(
        dayColors: Map<LocalDate, List<Int>>,
        weeks: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        backgroundStyle: Int = WidgetPreferences.BACKGROUND_TRANSPARENT,
    ): Bitmap {
        val today = LocalDate.now()
        val todayDow = today.dayOfWeek.ordinal // Mon=0 … Sun=6
        val startDate = today.minusDays(todayDow.toLong()).minusWeeks((weeks - 1).toLong())

        require(weeks > 0) { "weeks must be > 0" }
        val gap = maxOf(1, (minOf(bitmapWidth.toFloat() / weeks, bitmapHeight.toFloat() / ROWS) * 0.12f).toInt())
        val cellW = (bitmapWidth  - (weeks - 1) * gap) / weeks
        val cellH = (bitmapHeight - (ROWS  - 1) * gap) / ROWS
        val corner = maxOf(2f, minOf(cellW, cellH) / 5f)

        val inactiveColor = if (backgroundStyle == WidgetPreferences.BACKGROUND_LIGHT) LIGHT_INACTIVE else DARK_INACTIVE

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        when (backgroundStyle) {
            WidgetPreferences.BACKGROUND_DARK  -> canvas.drawColor(DARK_BACKGROUND)
            WidgetPreferences.BACKGROUND_LIGHT -> canvas.drawColor(LIGHT_BACKGROUND)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cellRect = RectF()
        val clipPath = Path()

        for (col in 0 until weeks) {
            for (row in 0 until ROWS) {
                val date = startDate.plusDays((col * 7L + row))
                if (date > today) continue

                val left  = col * (cellW + gap)
                val top   = row * (cellH + gap)
                cellRect.set(left.toFloat(), top.toFloat(), (left + cellW).toFloat(), (top + cellH).toFloat())

                val colors = dayColors[date] ?: emptyList()

                when (colors.size) {
                    0 -> {
                        paint.color = inactiveColor
                        canvas.drawRoundRect(cellRect, corner, corner, paint)
                    }
                    1 -> {
                        paint.color = colors[0]
                        canvas.drawRoundRect(cellRect, corner, corner, paint)
                    }
                    else -> {
                        // Clip to rounded cell shape, then draw rectangular bands
                        clipPath.rewind()
                        clipPath.addRoundRect(cellRect, corner, corner, Path.Direction.CW)
                        canvas.save()
                        canvas.clipPath(clipPath)

                        val bandH = cellH.toFloat() / colors.size
                        colors.forEachIndexed { i, color ->
                            paint.color = color
                            val bTop = top + i * bandH
                            val bBot = top + (i + 1) * bandH
                            canvas.drawRect(left.toFloat(), bTop, (left + cellW).toFloat(), bBot, paint)
                        }

                        canvas.restore()
                    }
                }
            }
        }

        // Fade the leftmost column only for transparent background
        // (DST_IN would punch a hole through a solid background)
        if (backgroundStyle == WidgetPreferences.BACKGROUND_TRANSPARENT) {
            val fadeRight = (cellW + gap).toFloat()
            val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, fadeRight, 0f,
                    Color.TRANSPARENT, Color.BLACK,
                    Shader.TileMode.CLAMP,
                )
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            canvas.drawRect(0f, 0f, fadeRight, bitmapHeight.toFloat(), fadePaint)
        }

        return bitmap
    }
}
