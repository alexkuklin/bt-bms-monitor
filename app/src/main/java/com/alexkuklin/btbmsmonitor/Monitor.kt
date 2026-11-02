package com.alexkuklin.btbmsmonitor

import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.exp

enum class ConnState { Idle, Scanning, Connecting, Connected }

sealed interface DumpState {
    data object Idle : DumpState
    data class Running(val done: Int, val total: Int) : DumpState
    data class Done(val json: String, val fileName: String) : DumpState
    data class Failed(val reason: String) : DumpState
}

data class Sample(val tMs: Long, val amps: Float)

data class UiState(
    val conn: ConnState = ConnState.Idle,
    val devices: List<Device> = emptyList(),
    val deviceName: String? = null,
    val info: BasicInfo? = null,
    val cells: List<Float> = emptyList(),
    /** Current smoothed over ~30 s, used for the time-to-full/empty estimate. */
    val smoothedAmps: Float = 0f,
    val history: List<Sample> = emptyList(),
    val message: String? = null,
    val settings: AlertSettings = AlertSettings(),
    val alerts: Set<Alert> = emptySet(),
    val dump: DumpState = DumpState.Idle,
)

/** Process-wide owner of the BLE connection, so it survives the UI and runs under [MonitorService]. */
class Monitor private constructor(private val app: Context) : BleClient.Listener {

    companion object {
        @Volatile private var instance: Monitor? = null
        fun get(ctx: Context): Monitor = instance ?: synchronized(this) {
            instance ?: Monitor(ctx.applicationContext).also { instance = it }
        }

        const val HISTORY_WINDOW_MS = 30 * 60 * 1000L
        private const val SMOOTHING_TAU_S = 30.0
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("jbd", Context.MODE_PRIVATE)
    private val client = BleClient(app, this)
    private val _ui = MutableStateFlow(UiState(settings = loadSettings()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val alertEngine = AlertEngine()
    private val notifier = AlertNotifier(app)
    private val found = LinkedHashMap<String, Device>()
    private val history = ArrayList<Sample>()
    private var assembler = FrameAssembler()
    private var lastSampleMs = 0L
    private var smoothed = 0f
    private var poll: Job? = null
    private var reconnect: Job? = null
    private var scanStop: Job? = null
    private var dumpJob: Job? = null
    /** Request waiting for a response during a dump (main thread only). */
    private var pending: Pair<Int, CompletableDeferred<Frame>>? = null

    /** Address we should stay connected to; null once the user disconnects. */
    private var wantAddress: String? = null

    fun autoConnect() {
        if (_ui.value.conn != ConnState.Idle) return
        val addr = prefs.getString("addr", null)
        if (addr != null) connect(Device(addr, prefs.getString("name", null), 0, true)) else startScan()
    }

    fun startScan() {
        if (!client.isEnabled) return _ui.update { it.copy(message = "Bluetooth is off") }
        found.clear()
        _ui.update { it.copy(conn = ConnState.Scanning, devices = emptyList(), message = null) }
        client.startScan()
        scanStop?.cancel()
        scanStop = scope.launch {
            delay(20_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanStop?.cancel()
        client.stopScan()
        _ui.update { if (it.conn == ConnState.Scanning) it.copy(conn = ConnState.Idle) else it }
    }

    fun connect(device: Device) {
        if (!client.isEnabled) return _ui.update { it.copy(message = "Bluetooth is off") }
        stopScan()
        wantAddress = device.address
        prefs.edit().putString("addr", device.address).putString("name", device.name).apply()
        _ui.update { UiState(conn = ConnState.Connecting, deviceName = device.name ?: device.address, settings = it.settings) }
        alertEngine.reset()
        ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java))
        client.connect(device.address)
    }

    fun disconnect() {
        wantAddress = null
        reconnect?.cancel()
        poll?.cancel()
        dumpJob?.cancel()
        client.close()
        prefs.edit().remove("addr").remove("name").apply()
        history.clear()
        alertEngine.reset()
        Alert.entries.forEach(notifier::clear)
        _ui.update { UiState(settings = it.settings) }
    }

    fun updateSettings(settings: AlertSettings) {
        prefs.edit()
            .putBoolean("lowSocOn", settings.lowSocEnabled).putInt("lowSoc", settings.lowSocPercent)
            .putBoolean("imbOn", settings.imbalanceEnabled).putInt("imbMv", settings.imbalanceMv)
            .apply()
        _ui.update { it.copy(settings = settings) }
        evaluateAlerts()
    }

    private fun loadSettings(): AlertSettings {
        val d = AlertSettings()
        return AlertSettings(
            prefs.getBoolean("lowSocOn", d.lowSocEnabled), prefs.getInt("lowSoc", d.lowSocPercent),
            prefs.getBoolean("imbOn", d.imbalanceEnabled), prefs.getInt("imbMv", d.imbalanceMv),
        )
    }

    private fun evaluateAlerts() {
        val s = _ui.value
        val active = alertEngine.update(s.settings, s.info?.soc, cellDeltaMv(s.cells))
        if (active == s.alerts) return
        _ui.update { it.copy(alerts = active) }
        val now = _ui.value
        (active - s.alerts).forEach { notifier.fire(it, alertText(it, now)) }
        (s.alerts - active).forEach(notifier::clear)
    }

    // --- BleClient.Listener (called on binder threads, hop to main) ---

    override fun onScanResult(device: Device) {
        scope.launch {
            found[device.address] = device
            val sorted = found.values.sortedWith(
                compareByDescending<Device> { it.looksLikeJbd }
                    .thenByDescending { it.name != null }
                    .thenByDescending { it.rssi }
            )
            _ui.update { it.copy(devices = sorted) }
        }
    }

    override fun onScanFailed(code: Int) {
        scope.launch { _ui.update { it.copy(conn = ConnState.Idle, message = "Scan failed ($code)") } }
    }

    override fun onConnected() {
        scope.launch {
            assembler = FrameAssembler()
            history.clear()
            lastSampleMs = 0L
            _ui.update { it.copy(conn = ConnState.Connected, message = null, history = emptyList()) }
            startPolling()
        }
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch {
            while (true) {
                client.send(JbdProtocol.REQUEST_BASIC)
                delay(400)
                client.send(JbdProtocol.REQUEST_CELLS)
                delay(600)
            }
        }
    }

    // --- Read-only parameter dump ---

    fun dumpParameters() {
        if (dumpJob?.isActive == true || _ui.value.conn != ConnState.Connected) return
        val regs = mutableListOf<RegisterRead>()
        val addrs = listOf(JbdProtocol.REG_HW_VERSION) + JbdProtocol.DUMP_REGISTERS.toList()
        dumpJob = scope.launch {
            poll?.cancel()
            delay(300) // let an in-flight poll response arrive
            var inFactory = false
            try {
                _ui.update { it.copy(dump = DumpState.Running(0, addrs.size)) }
                var timeouts = 0
                for ((n, reg) in addrs.withIndex()) {
                    if (reg == JbdProtocol.DUMP_REGISTERS.first) {
                        transact(JbdProtocol.ENTER_FACTORY, 0x00) ?: error("BMS did not accept factory mode")
                        inFactory = true
                    }
                    val f = transact(JbdProtocol.readRequest(reg), reg)
                    if (f == null) {
                        regs += RegisterRead(reg, -1, ByteArray(0))
                        if (++timeouts >= 5) error("BMS stopped responding")
                    } else {
                        timeouts = 0
                        regs += RegisterRead(reg, f.status, f.data)
                    }
                    _ui.update { it.copy(dump = DumpState.Running(n + 1, addrs.size)) }
                }
                val stamp = java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                val name = _ui.value.deviceName
                val json = RegisterDump.toJson(name, appVersion(), stamp, regs)
                val file = "jbd-dump-" + stamp.take(19).replace(":", "").replace("-", "").replace("T", "-") + ".json"
                _ui.update { it.copy(dump = DumpState.Done(json, file)) }
            } catch (e: CancellationException) {
                _ui.update { it.copy(dump = DumpState.Idle) }
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(dump = DumpState.Failed(e.message ?: "Dump failed")) }
            } finally {
                withContext(NonCancellable) {
                    if (inFactory) {
                        client.send(JbdProtocol.EXIT_FACTORY)
                        delay(300)
                    }
                    if (_ui.value.conn == ConnState.Connected) startPolling()
                }
            }
        }
    }

    fun dumpConsumed() = _ui.update { it.copy(dump = DumpState.Idle) }

    /** Sends [request] and waits for the frame with command [cmd]; null on timeout. */
    private suspend fun transact(request: ByteArray, cmd: Int): Frame? {
        val d = CompletableDeferred<Frame>()
        pending = cmd to d
        client.send(request)
        return try {
            withTimeout(1_500) { d.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            null
        } finally {
            pending = null
        }
    }

    private fun appVersion() = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    override fun onDisconnected() {
        scope.launch {
            poll?.cancel()
            dumpJob?.cancel()
            val addr = wantAddress
            if (addr == null) {
                _ui.update { it.copy(conn = ConnState.Idle) }
                return@launch
            }
            _ui.update { it.copy(conn = ConnState.Connecting, message = "Connection lost, retrying…") }
            reconnect?.cancel()
            reconnect = launch {
                delay(3_000)
                if (wantAddress == addr) client.connect(addr)
            }
        }
    }

    override fun onError(message: String) {
        scope.launch {
            wantAddress = null
            prefs.edit().remove("addr").remove("name").apply()
            _ui.update { it.copy(message = message) }
        }
    }

    override fun onData(bytes: ByteArray) {
        scope.launch {
            for (f in assembler.feed(bytes)) {
                val waiting = pending
                if (waiting != null && waiting.first == f.cmd) {
                    waiting.second.complete(f)
                    continue
                }
                if (f.status != 0) continue
                when (f.cmd) {
                    JbdProtocol.CMD_BASIC -> JbdProtocol.parseBasic(f.data)?.let(::onBasic)
                    JbdProtocol.CMD_CELLS -> {
                        _ui.update { it.copy(cells = JbdProtocol.parseCells(f.data)) }
                        evaluateAlerts()
                    }
                }
            }
        }
    }

    private fun onBasic(info: BasicInfo) {
        val now = System.currentTimeMillis()
        smoothed = if (lastSampleMs == 0L) info.current else {
            val alpha = 1.0 - exp(-((now - lastSampleMs) / 1000.0) / SMOOTHING_TAU_S)
            smoothed + (alpha * (info.current - smoothed)).toFloat()
        }
        lastSampleMs = now
        history += Sample(now, info.current)
        val cutoff = now - HISTORY_WINDOW_MS
        val stale = history.indexOfFirst { it.tMs >= cutoff }.let { if (it < 0) history.size else it }
        if (stale > 0) history.subList(0, stale).clear()
        _ui.update { it.copy(info = info, smoothedAmps = smoothed, history = history.toList()) }
        evaluateAlerts()
    }
}
