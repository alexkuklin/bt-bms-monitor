package com.alexkuklin.btbmsmonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

val ChargeColor = Color(0xFF34C77B)
val DischargeColor = Color(0xFFFF9F43)

private const val MAX_AMPS = 200f
private const val LOG_KNEE = 1f // amps at which the log curve has its unit scale
private const val SWEEP = 135f // degrees each side of top-centre

/** 0..1 position along one half of the logarithmic scale, 0 A -> 0, 200 A -> 1. */
fun logFraction(amps: Float): Float {
    val x = abs(amps).coerceAtMost(MAX_AMPS)
    return ln(1f + x / LOG_KNEE) / ln(1f + MAX_AMPS / LOG_KNEE)
}

private val ticks = listOf(1, 2, 5, 10, 20, 50, 100, 200)

/**
 * Gauge with a logarithmic scale of 0-200 A in both directions: zero at the top,
 * discharge sweeping left (orange), charge sweeping right (green).
 */
@Composable
fun CurrentGauge(amps: Float, volts: Float, watts: Float, soc: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = tickColor)
    val zeroTick = MaterialTheme.colorScheme.onSurface

    Box(modifier.widthIn(max = 360.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val labelSpace = 26.dp.toPx()
            val r = size.minDimension / 2 - labelSpace - stroke / 2
            val topLeft = Offset(center.x - r, center.y - r)
            val arcSize = Size(2 * r, 2 * r)

            drawArc(track, -90f - SWEEP, 2 * SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val frac = logFraction(amps)
            if (frac > 0.002f) {
                val dir = if (amps >= 0) 1f else -1f
                drawArc(
                    if (amps >= 0) ChargeColor else DischargeColor,
                    -90f, dir * frac * SWEEP, false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }

            fun polar(angleDeg: Float, radius: Float): Offset {
                val a = angleDeg * PI.toFloat() / 180f
                return Offset(center.x + radius * cos(a), center.y + radius * sin(a))
            }

            fun label(text: String, angleDeg: Float, color: Color = tickColor) {
                val layout = measurer.measure(text, labelStyle.copy(color = color))
                val p = polar(angleDeg, r + stroke / 2 + 16.dp.toPx())
                drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
            }

            label("0", -90f, zeroTick)
            for (sign in intArrayOf(-1, 1)) {
                for (t in ticks) {
                    val angle = -90f + sign * logFraction(t.toFloat()) * SWEEP
                    drawLine(
                        tickColor,
                        polar(angle, r + stroke / 2 + 2.dp.toPx()),
                        polar(angle, r + stroke / 2 + 7.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    label(if (sign < 0) "-$t" else "$t", angle)
                }
            }
        }
        Column(Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                String.format(Locale.US, "%+.2f", amps),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Text(
                when {
                    amps > 0.05f -> "A  charging"
                    amps < -0.05f -> "A  discharging"
                    else -> "A  idle"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GaugeStat(String.format(Locale.US, "%.2f V", volts), "voltage")
                GaugeStat(String.format(Locale.US, "%+.0f W", watts), "power")
                GaugeStat("$soc %", "charge")
            }
        }
    }
}

@Composable
private fun GaugeStat(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
