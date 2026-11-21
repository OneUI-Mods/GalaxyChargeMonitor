package dev.ryuya.galaxycharge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
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

    private val readings = if (temperature) {
        listOf(samples.map { it.temperature("temp") }, samples.map { it.temperature("lrp") })
    } else listOf(samples.map(Snapshot::batteryWatts))
    private val range = run {
        var minValue = Double.POSITIVE_INFINITY
        var maxValue = Double.NEGATIVE_INFINITY
        for (series in readings) for (value in series) {
            if (value == null) continue
            minValue = min(minValue, value)
            maxValue = max(maxValue, value)
        }
        if (minValue == Double.POSITIVE_INFINITY) null else minValue to maxValue
    }
    private val low = range?.first?.let { if (temperature) it - 2 else min(0.0, it - 1) } ?: 0.0
    private val high = max(low + 1, (range?.second ?: low) + 2)
    private val paths = readings.map { Path() }
    private val labels = Array(4) { "" }
    private var labelLocale: Locale? = null
    private var plotDensity = 0f
    private var left = 0f
    private var top = 0f
    private var bottom = 0f
    private var right = 0f
    private var plottedWidth = -1
    private var plottedHeight = -1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2 || range == null) return
        val metrics = resources.displayMetrics
        if (plottedWidth != width || plottedHeight != height || plotDensity != metrics.density) {
            rebuildPaths(metrics.density)
        }
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        if (locale != labelLocale) {
            for (i in labels.indices) labels[i] = String.format(locale, "%.0f", low + (high - low) * i / 3)
            labelLocale = locale
        }
        paint.textSize = 11 * metrics.scaledDensity
        paint.color = textColor
        paint.strokeWidth = metrics.density
        for (i in labels.indices) {
            val y = bottom - (bottom - top) * i / 3
            paint.alpha = 50
            canvas.drawLine(left, y, right, y, paint)
            paint.alpha = 255
            canvas.drawText(labels[i], 0f, y + 4, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * metrics.density
        for (index in paths.indices) {
            paint.color = if (index == 0) primary else secondary
            canvas.drawPath(paths[index], paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun rebuildPaths(density: Float) {
        plottedWidth = width
        plottedHeight = height
        plotDensity = density
        left = 46 * density
        top = 24 * density
        bottom = height - 32 * density
        right = width - 12 * density
        val start = samples.first().time
        val duration = max(1L, samples.last().time - start)
        for (index in paths.indices) {
            val line = paths[index]
            line.rewind()
            var pen = false
            var previous = start
            for (position in samples.indices) {
                val sample = samples[position]
                val value = readings[index][position]
                if (value == null || sample.error != null) {
                    pen = false
                    continue
                }
                val x = left + (sample.time - start).toFloat() / duration * (right - left)
                val y = bottom - ((value - low) / (high - low) * (bottom - top)).toFloat()
                if (!pen || sample.time - previous > 90_000) line.moveTo(x, y) else line.lineTo(x, y)
                pen = true
                previous = sample.time
            }
        }
    }
}
