package com.alexkuklin.jbdmonitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** UI-facing handle on the process-wide [Monitor]. */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val monitor = Monitor.get(app)
    val ui = monitor.ui

    fun autoConnect() = monitor.autoConnect()
    fun startScan() = monitor.startScan()
    fun connect(device: Device) = monitor.connect(device)
    fun disconnect() = monitor.disconnect()
    fun dumpParameters() = monitor.dumpParameters()
    fun dumpConsumed() = monitor.dumpConsumed()
    fun updateSettings(s: AlertSettings) = monitor.updateSettings(s)
}
