package it.spaceanalyzer.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.spaceanalyzer.mobile.link.Series

/** Min/max per horizontal bucket: draws at most 2 points per pixel column whatever the sample rate. */
class Decimated(val x: IntArray, val min: DoubleArray, val max: DoubleArray)

fun decimate(series: Series, startUs: Long, endUs: Long, buckets: Int): Decimated {
    val mins = DoubleArray(buckets) { Double.NaN }
    val maxs = DoubleArray(buckets) { Double.NaN }
    val span = (endUs - startUs).coerceAtLeast(1)
    for (i in 0 until series.size) {
        val t = series.timesUs[i]
        if (t < startUs || t > endUs) continue
        val b = ((t - startUs) * buckets / span).toInt().coerceIn(0, buckets - 1)
        val v = series.values[i]
        if (v.isNaN()) continue
        if (mins[b].isNaN() || v < mins[b]) mins[b] = v
        if (maxs[b].isNaN() || v > maxs[b]) maxs[b] = v
    }
    val idx = (0 until buckets).filter { !mins[it].isNaN() }
    return Decimated(idx.toIntArray(), DoubleArray(idx.size) { mins[idx[it]] }, DoubleArray(idx.size) { maxs[idx[it]] })
}

/** Value of the last sample at or before [tUs]; null if none, or if it is older than [maxAgeUs]. */
fun valueAt(series: Series, tUs: Long, maxAgeUs: Long = 10_000_000): Double? {
    var lo = 0; var hi = series.size - 1; var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (series.timesUs[mid] <= tUs) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return if (found >= 0 && tUs - series.timesUs[found] <= maxAgeUs) series.values[found] else null
}

const val MIN_WINDOW_US = 2_000_000L
const val MAX_WINDOW_US = 600_000_000L

/**
 * Chart with pinch zoom, drag pan and a tap cursor. Gestures only report intent (fractions of the
 * width); the caller owns the window, the view end and the cursor so the state survives recomposition.
 */
@Composable
fun TrendChart(
    series: List<Pair<Color, Series>>, windowUs: Long, endUs: Long, cursorUs: Long?, axis: Color, grid: Color,
    onPan: (Float) -> Unit, onZoom: (Float) -> Unit, onTap: (Float) -> Unit, modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val pan by rememberUpdatedState(onPan)
    val zoom by rememberUpdatedState(onZoom)
    val tap by rememberUpdatedState(onTap)
    val label = TextStyle(color = axis, fontSize = 10.sp)
    Canvas(modifier.fillMaxWidth().height(240.dp)
        .pointerInput(Unit) {
            detectTransformGestures { _, p, z, _ ->
                if (z != 1f) zoom(z)
                if (p.x != 0f) pan(p.x / size.width)
            }
        }
        .pointerInput(Unit) { detectTapGestures { tap((it.x / size.width).coerceIn(0f, 1f)) } }) {
        val w = size.width.toInt().coerceAtLeast(2)
        val startUs = endUs - windowUs
        val decimated = series.map { (c, s) -> c to decimate(s, startUs, endUs, w) }
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        decimated.forEach { (_, d) -> d.min.forEach { lo = minOf(lo, it) }; d.max.forEach { hi = maxOf(hi, it) } }
        drawRect(grid, style = Stroke(1f))
        if (lo > hi) return@Canvas
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.05; lo -= pad; hi += pad
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        for (i in 1..3) {
            val gy = size.height * i / 4
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), 1f)
        }
        drawText(measurer, "%.4g".format(hi), Offset(4f, 2f), label)
        drawText(measurer, "%.4g".format(lo), Offset(4f, size.height - 16.sp.toPx()), label)
        decimated.forEach { (color, d) ->
            val path = Path()
            for (i in d.x.indices) {
                val x = d.x[i].toFloat()
                if (i == 0) path.moveTo(x, y(d.max[i])) else path.lineTo(x, y(d.max[i]))
                if (d.min[i] != d.max[i]) path.lineTo(x, y(d.min[i]))
            }
            drawPath(path, color, style = Stroke(2f))
            if (d.x.size == 1) drawCircle(color, 3f, Offset(d.x[0].toFloat(), y(d.max[0])))
        }
        if (cursorUs != null && cursorUs in startUs..endUs) {
            val cx = ((cursorUs - startUs).toDouble() / windowUs * size.width).toFloat()
            drawLine(axis, Offset(cx, 0f), Offset(cx, size.height), 2f)
        }
    }
}
