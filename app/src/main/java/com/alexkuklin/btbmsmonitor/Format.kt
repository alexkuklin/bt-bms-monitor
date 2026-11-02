package com.alexkuklin.btbmsmonitor

import kotlin.math.roundToInt

/** "Time to full" / "Time to empty" estimate from remaining capacity and (smoothed) current. */
data class Eta(val label: String, val hours: Float)

fun estimateEta(info: BasicInfo, smoothedAmps: Float): Eta? {
    if (info.nominalAh <= 0f) return null
    return when {
        smoothedAmps > 0.3f ->
            Eta("Time to full", (info.nominalAh - info.remainingAh).coerceAtLeast(0f) / smoothedAmps)
        smoothedAmps < -0.3f ->
            Eta("Time to empty", info.remainingAh / -smoothedAmps)
        else -> null
    }
}

fun formatDuration(hours: Float): String {
    val totalMin = (hours * 60).roundToInt()
    return when {
        totalMin >= 100 * 60 -> ">100 h"
        totalMin >= 60 -> "${totalMin / 60} h ${totalMin % 60} min"
        totalMin >= 1 -> "$totalMin min"
        else -> "< 1 min"
    }
}
