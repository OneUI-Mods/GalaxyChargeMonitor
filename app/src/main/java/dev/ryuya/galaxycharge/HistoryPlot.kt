package dev.ryuya.galaxycharge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/** A bounded, hardware-accelerated View. It redraws only when a new sample is displayed. */
internal class HistoryPlot(
    context: Context,
    private val samples: List<Snapshot>,
    private val temperature: Boolean,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val secondary =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
    private val textColor =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) return
        val all = samples.flatMap { s ->
            if (temperature) listOfNotNull(s.temperature("temp"), s.temperature("lrp"))
            else listOfNotNull(s.batteryWatts())
        }
        if (all.isEmpty()) return
        val low = if (temperature) all.min() - 2 else min(0.0, all.min() - 1)
        val high = max(low + 1, all.max() + 2)
        val left = 46 * resources.displayMetrics.density
        val top = 24 * resources.displayMetrics.density
        val bottom = height - 32 * resources.displayMetrics.density
        val right = width - 12 * resources.displayMetrics.density
        paint.textSize = 11 * resources.displayMetrics.scaledDensity
        paint.color = textColor
        for (i in 0..3) {
            val v = low + (high - low) * i / 3
            val y = (bottom - (bottom - top) * i / 3)
            paint.strokeWidth = resources.displayMetrics.density
            paint.alpha = 50
            canvas.drawLine(left, y, right, y, paint)
            paint.alpha = 255
            canvas.drawText("%.0f".format(v), 0f, y + 4, paint)
        }
        val start = samples.first().time
        val duration = max(1L, samples.last().time - start)
        val series = if (temperature) listOf("temp", "lrp") else listOf("power")
        series.forEachIndexed { index, key ->
            val line = Path()
            var pen = false
            var previous = start
            for (s in samples) {
                val v = if (key == "power") s.batteryWatts() else s.temperature(key)
                if (v == null || s.error != null) {
                    pen = false
                    continue
                }
                val x = left + (s.time - start).toFloat() / duration * (right - left)
                val y = bottom - ((v - low) / (high - low) * (bottom - top)).toFloat()
                if (!pen || s.time - previous > 90_000) line.moveTo(x, y) else line.lineTo(x, y)
                pen = true
                previous = s.time
            }
            paint.color = if (index == 0) primary else secondary
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * resources.displayMetrics.density
            canvas.drawPath(line, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
