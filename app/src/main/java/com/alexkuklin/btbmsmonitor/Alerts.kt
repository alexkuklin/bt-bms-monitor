package com.alexkuklin.btbmsmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Locale

enum class Alert { LowSoc, Imbalance }

data class AlertSettings(
    val lowSocEnabled: Boolean = true,
    val lowSocPercent: Int = 20,
    val imbalanceEnabled: Boolean = true,
    val imbalanceMv: Int = 100,
)

/**
 * Decides which alerts are active. Each one latches when its threshold is crossed and only
 * clears after a margin (3 % SoC / 20 % of the mV limit), so values hovering at the limit don't flap.
 */
class AlertEngine {
    private var low = false
    private var imbalance = false

    fun reset() {
        low = false
        imbalance = false
    }

    fun update(s: AlertSettings, soc: Int?, deltaMv: Int?): Set<Alert> {
        if (!s.lowSocEnabled) low = false
        else if (soc != null) {
            if (!low && soc <= s.lowSocPercent) low = true
            else if (low && soc >= s.lowSocPercent + 3) low = false
        }
        if (!s.imbalanceEnabled) imbalance = false
        else if (deltaMv != null) {
            if (!imbalance && deltaMv >= s.imbalanceMv) imbalance = true
            else if (imbalance && deltaMv < s.imbalanceMv * 0.8) imbalance = false
        }
        return buildSet {
            if (low) add(Alert.LowSoc)
            if (imbalance) add(Alert.Imbalance)
        }
    }
}

fun cellDeltaMv(cells: List<Float>): Int? {
    if (cells.size < 2) return null
    return Math.round((cells.max() - cells.min()) * 1000)
}

fun alertText(alert: Alert, s: UiState): String = when (alert) {
    Alert.LowSoc -> "Low battery: ${s.info?.soc ?: "?"} % (limit ${s.settings.lowSocPercent} %)"
    Alert.Imbalance ->
        String.format(Locale.US, "Cell imbalance: %d mV (limit %d mV)", cellDeltaMv(s.cells) ?: 0, s.settings.imbalanceMv)
}

/** Posts a high-importance notification when an alert fires and removes it when it clears. */
class AlertNotifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Battery alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun fire(alert: Alert, text: String) {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            ID_BASE + alert.ordinal,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("JBD battery alert")
                .setContentText(text)
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )
    }

    fun clear(alert: Alert) = nm.cancel(ID_BASE + alert.ordinal)

    private companion object {
        const val CHANNEL = "alerts"
        const val ID_BASE = 10
    }
}
