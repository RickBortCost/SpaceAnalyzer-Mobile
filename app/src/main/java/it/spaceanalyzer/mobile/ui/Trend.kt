package it.spaceanalyzer.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
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

val TrendColors = listOf(Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00ACC1))

@Composable
fun TrendChart(series: List<Pair<Color, Series>>, windowUs: Long, endUs: Long, axis: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val w = size.width.toInt().coerceAtLeast(2)
        val startUs = endUs - windowUs
        val decimated = series.map { (c, s) -> c to decimate(s, startUs, endUs, w) }
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        decimated.forEach { (_, d) -> d.min.forEach { lo = minOf(lo, it) }; d.max.forEach { hi = maxOf(hi, it) } }
        if (lo > hi) return@Canvas
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.05; lo -= pad; hi += pad
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        drawRect(axis.copy(alpha = 0.4f), style = Stroke(1f))
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
    }
}
