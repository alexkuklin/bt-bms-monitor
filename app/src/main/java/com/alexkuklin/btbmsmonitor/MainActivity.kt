package com.alexkuklin.btbmsmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.systemBarsPadding()) { App(vm) }
                }
            }
        }
    }
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Notification permission is requested too, but the app works without it (only the status notification is hidden). */
private fun requestedPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) requiredPermissions() + Manifest.permission.POST_NOTIFICATIONS else requiredPermissions()

private fun hasAll(ctx: Context, perms: Array<String>) =
    perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@Composable
private fun App(vm: MonitorViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val perms = remember { requiredPermissions() }
    var granted by remember { mutableStateOf(hasAll(ctx, perms)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasAll(ctx, perms)
    }
    LaunchedEffect(granted) { if (granted) vm.autoConnect() }

    when {
        !granted -> CenteredColumn {
            Text("Bluetooth permission is needed to talk to the battery.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(requestedPermissions()) }) { Text("Grant permission") }
        }
        ui.conn == ConnState.Connected || (ui.conn == ConnState.Connecting && ui.info != null) -> Dashboard(ui, vm)
        else -> ScanScreen(ui, vm)
    }
}

@Composable
private fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
    content = content,
)

@Composable
private fun ScanScreen(ui: UiState, vm: MonitorViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("BT BMS Monitor", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        when (ui.conn) {
            ConnState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Connecting to ${ui.deviceName ?: "battery"}…")
            }
            ConnState.Scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Scanning…")
            }
            else -> Button(onClick = vm::startScan) { Text("Scan for batteries") }
        }
        ui.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (ui.conn == ConnState.Connecting) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::disconnect) { Text("Cancel") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.devices, key = { it.address }) { d ->
                Card(Modifier.fillMaxWidth().clickable { vm.connect(d) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name ?: "(unnamed)", fontWeight = FontWeight.Medium)
                            Text(d.address, style = MaterialTheme.typography.bodySmall)
                        }
                        if (d.looksLikeJbd) Text("JBD  ", color = ChargeColor, fontWeight = FontWeight.Bold)
                        Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun Dashboard(ui: UiState, vm: MonitorViewModel) {
    LocalView.current.keepScreenOn = true
    val info = ui.info ?: return
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ui.deviceName ?: "Battery", style = MaterialTheme.typography.titleMedium)
                Text(
                    ui.message ?: "Connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
        }

        if (ui.alerts.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    ui.alerts.forEach { Text("⚠ " + alertText(it, ui), fontWeight = FontWeight.Bold) }
                }
            }
        }

        Section("Current") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CurrentGauge(info.current, info.packVoltage, info.powerW, info.soc) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Capacity", Modifier.weight(1f)) {
                Text(
                    String.format(Locale.US, "%.1f Ah", info.remainingAh),
                    style = MaterialTheme.typography.headlineMedium,
                )
                LinearProgressIndicator(progress = { info.soc / 100f }, Modifier.fillMaxWidth())
                if (info.nominalAh > 0f) {
                    Spacer(Modifier.height(4.dp))
                    Text(String.format(Locale.US, "of %.1f Ah", info.nominalAh), style = MaterialTheme.typography.bodySmall)
                }
            }
            val eta = estimateEta(info, ui.smoothedAmps)
            Section(eta?.label ?: "Time remaining", Modifier.weight(1f)) {
                Text(eta?.let { formatDuration(it.hours) } ?: "—", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (eta == null) "idle" else "at ${String.format(Locale.US, "%.1f", kotlin.math.abs(ui.smoothedAmps))} A avg",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        CellsSection(ui.cells, info.balanceMask)

        Section("Temperature sensors") {
            if (info.temps.isEmpty()) Text("No sensors detected")
            else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                info.temps.forEachIndexed { i, t ->
                    Column {
                        Text("T${i + 1}", style = MaterialTheme.typography.bodySmall)
                        Text(String.format(Locale.US, "%.1f °C", t), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        Section("Current history (up to 30 min)") {
            CurrentGraph(ui.history)
        }

        AlertSettingsSection(ui.settings, vm::updateSettings)

        if (BuildConfig.PARAM_DUMP) DumpSection(ui.dump, vm)

        Section("Status") {
            Text(
                "Charge FET ${if (info.chargeFetOn) "on" else "off"} · Discharge FET ${if (info.dischargeFetOn) "on" else "off"}",
            )
            Text("${info.cycles} cycles")
            val flags = JbdProtocol.protectionFlags(info.protection)
            if (flags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Protection: ${flags.joinToString(", ")}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CellsSection(cells: List<Float>, balanceMask: Long) {
    val min = cells.minOrNull()
    val max = cells.maxOrNull()
    val deltaMv = if (min != null && max != null) ((max - min) * 1000).toInt() else null
    Section("Cells") {
        if (cells.isEmpty()) {
            Text("Waiting for data…")
            return@Section
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Δ ${deltaMv} mV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                String.format(Locale.US, "min %.3f · max %.3f V", min, max),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        cells.withIndex().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                row.forEach { (i, v) ->
                    val color = when {
                        deltaMv == 0 || min == max -> MaterialTheme.colorScheme.onSurface
                        v == max -> ChargeColor
                        v == min -> DischargeColor
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text("C${i + 1}", Modifier.width(34.dp), style = MaterialTheme.typography.bodySmall)
                        Text(String.format(Locale.US, "%.3f V", v), color = color, fontWeight = FontWeight.Medium)
                        if (balanceMask and (1L shl i) != 0L) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(8.dp).background(Color(0xFF42A5F5), CircleShape))
                        }
                    }
                }
            }
        }
        if (balanceMask != 0L) {
            Spacer(Modifier.height(4.dp))
            Text("● balancing", style = MaterialTheme.typography.bodySmall, color = Color(0xFF42A5F5))
        }
    }
}

@Composable
private fun AlertSettingsSection(s: AlertSettings, onChange: (AlertSettings) -> Unit) {
    Section("Alerts") {
        AlertRow(
            "Low charge at or below", s.lowSocEnabled, "${s.lowSocPercent} %",
            onToggle = { onChange(s.copy(lowSocEnabled = it)) },
            onStep = { onChange(s.copy(lowSocPercent = (s.lowSocPercent + it * 5).coerceIn(5, 50))) },
        )
        AlertRow(
            "Cell imbalance at or above", s.imbalanceEnabled, "${s.imbalanceMv} mV",
            onToggle = { onChange(s.copy(imbalanceEnabled = it)) },
            onStep = { onChange(s.copy(imbalanceMv = (s.imbalanceMv + it * 10).coerceIn(10, 500))) },
        )
    }
}

@Composable
private fun AlertRow(label: String, enabled: Boolean, value: String, onToggle: (Boolean) -> Unit, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Switch(enabled, onToggle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = { onStep(-1) }, enabled = enabled) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onStep(1) }, enabled = enabled) { Text("+") }
    }
}

@Composable
private fun DumpSection(dump: DumpState, vm: MonitorViewModel) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<DumpState.Done?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val d = pending
        if (uri != null && d != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(d.json.toByteArray()) } != null
            }.getOrDefault(false)
            Toast.makeText(ctx, if (ok) "Dump saved" else "Could not save dump", Toast.LENGTH_SHORT).show()
        }
        pending = null
        vm.dumpConsumed()
    }
    LaunchedEffect(dump) {
        if (dump is DumpState.Done && pending == null) {
            pending = dump
            saver.launch(dump.fileName)
        }
    }

    Section("BMS parameters") {
        when (dump) {
            is DumpState.Running -> {
                Text("Reading registers… ${dump.done}/${dump.total}")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { dump.done / dump.total.toFloat() }, Modifier.fillMaxWidth())
            }
            is DumpState.Failed -> {
                Text(dump.reason, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = vm::dumpConsumed) { Text("OK") }
            }
            else -> {
                Text("Save the raw BMS parameter registers to a file (read-only).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { confirm = true }) { Text("Dump parameters…") }
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Dump BMS parameters?") },
        text = {
            Text(
                "This briefly switches the BMS into factory mode to read its parameter registers (nothing is written), " +
                    "then switches back. Live monitoring pauses for about 15 seconds. Do not disconnect during the dump."
            )
        },
        confirmButton = { TextButton(onClick = { confirm = false; vm.dumpParameters() }) { Text("Dump") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
