package com.alexkuklin.btbmsmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service whose only job is to keep the process (and so the BLE connection held by
 * [Monitor]) alive while the app is in the background, and to show the live status in a notification.
 */
class MonitorService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val monitor = Monitor.get(this)
        if (intent?.action == ACTION_DISCONNECT) {
            monitor.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Battery status", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(statusText(monitor.ui.value)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        job?.cancel()
        job = monitor.scope.launch {
            monitor.ui.collect { if (it.conn == ConnState.Idle) stopSelf() }
        }
        monitor.scope.launch {
            monitor.ui.map(::statusText).distinctUntilChanged().collect { nm.notify(NOTIF_ID, build(it)) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
    }

    private fun statusText(s: UiState): String {
        val info = s.info ?: return "Connecting to ${s.deviceName ?: "battery"}…"
        val base = String.format(Locale.US, "%d %% · %+.1f A · %+.0f W", info.soc, info.current, info.powerW)
        return if (s.conn == ConnState.Connected) base else "$base (reconnecting…)"
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("JBD battery")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val CHANNEL = "status"
        const val NOTIF_ID = 1
        const val ACTION_DISCONNECT = "com.alexkuklin.btbmsmonitor.DISCONNECT"
    }
}
