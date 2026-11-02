package com.alexkuklin.btbmsmonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

private val yScales = listOf(5f, 10f, 20f, 50f, 100f, 200f)

/** Linear-scale plot of current over the retained history (up to 30 min since connecting). */
@Composable
fun CurrentGraph(history: List<Sample>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zeroLine = MaterialTheme.colorScheme.outline
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        val left = 44.dp.toPx()
        val right = 6.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 20.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom

        if (history.size < 2) {
            val l = measurer.measure("Collecting data…", labelStyle)
            drawText(l, topLeft = Offset(left + (w - l.size.width) / 2, top + (h - l.size.height) / 2))
            return@Canvas
        }

        val now = history.last().tMs
        val span = (now - history.first().tMs).coerceIn(60_000L, Monitor.HISTORY_WINDOW_MS)
        val t0 = now - span
        val peak = max(5f, history.maxOf { abs(it.amps) })
        val yMax = yScales.firstOrNull { it >= peak } ?: peak

        fun x(t: Long) = left + (t - t0) / span.toFloat() * w
        fun y(a: Float) = top + h / 2 - a / yMax * h / 2

        fun yLabel(text: String, a: Float) {
            val l = measurer.measure(text, labelStyle)
            drawText(l, topLeft = Offset(left - l.size.width - 4.dp.toPx(), y(a) - l.size.height / 2f))
        }
        for (a in floatArrayOf(yMax, -yMax)) {
            drawLine(grid, Offset(left, y(a)), Offset(left + w, y(a)), 1.dp.toPx())
            yLabel(String.format("%+.0f A", a), a)
        }
        drawLine(zeroLine, Offset(left, y(0f)), Offset(left + w, y(0f)), 1.dp.toPx())
        yLabel("0", 0f)

        val path = Path()
        history.forEachIndexed { i, s ->
            if (i == 0) path.moveTo(x(s.tMs), y(s.amps)) else path.lineTo(x(s.tMs), y(s.amps))
        }
        drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val startText = "-" + formatSpan(span)
        val startLabel = measurer.measure(startText, labelStyle)
        val nowLabel = measurer.measure("now", labelStyle)
        val ly = top + h + 4.dp.toPx()
        drawText(startLabel, topLeft = Offset(left, ly))
        drawText(nowLabel, topLeft = Offset(left + w - nowLabel.size.width, ly))
    }
}

private fun formatSpan(ms: Long): String {
    val s = ms / 1000
    return if (s >= 120) "${s / 60} min" else "$s s"
}
