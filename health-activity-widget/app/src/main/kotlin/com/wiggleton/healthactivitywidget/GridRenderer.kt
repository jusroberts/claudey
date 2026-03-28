package com.wiggleton.healthactivitywidget

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
    private val BACKGROUND_COLOR = Color.parseColor("#0D1117")
    private val INACTIVE_COLOR   = Color.parseColor("#161B22")

    fun render(
        dayColors: Map<LocalDate, List<Int>>,
        weeks: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Bitmap {
        val today = LocalDate.now()
        val todayDow = today.dayOfWeek.value % 7
        val startDate = today.minusDays(todayDow.toLong()).minusWeeks((weeks - 1).toLong())

        val gap = maxOf(1, (minOf(bitmapWidth.toFloat() / weeks, bitmapHeight.toFloat() / ROWS) * 0.12f).toInt())
        val cellW = (bitmapWidth  - (weeks - 1) * gap) / weeks
        val cellH = (bitmapHeight - (ROWS  - 1) * gap) / ROWS
        val corner = maxOf(2f, minOf(cellW, cellH) / 5f)

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
//        canvas.drawColor(BACKGROUND_COLOR)

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
                        paint.color = INACTIVE_COLOR
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

        // Fade the leftmost column from transparent (left edge) to opaque (right edge)
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

        return bitmap
    }
}
