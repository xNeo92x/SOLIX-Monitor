package de.solixmonitor.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import de.solixmonitor.android.data.HistoryPoint
import de.solixmonitor.android.data.SolixSettings
import de.solixmonitor.android.data.SolixSnapshot
import de.solixmonitor.android.data.SolixUiState
import de.solixmonitor.android.data.TestConnectionState
import de.solixmonitor.android.ui.theme.BatteryViolet
import de.solixmonitor.android.ui.theme.GridBlue
import de.solixmonitor.android.ui.theme.SolarAmber
import de.solixmonitor.android.ui.theme.SolixGreen
import de.solixmonitor.android.ui.theme.SolixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLocalNetworkPermission()
        setContent {
            SolixTheme {
                val viewModel: SolixViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                SolixApp(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onOpenSettings = viewModel::showSettings,
                    onCloseSettings = viewModel::hideSettings,
                    onSaveSettings = viewModel::saveSettings,
                    onTestConnection = viewModel::testConnection,
                )
            }
        }
    }

    private fun requestLocalNetworkPermission() {
        if (
            Build.VERSION.SDK_INT == 36 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
                LOCAL_NETWORK_PERMISSION_REQUEST,
            )
        }
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION_REQUEST = 1001
    }
}

@Composable
private fun SolixApp(
    state: SolixUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onSaveSettings: (SolixSettings) -> Result<Unit>,
    onTestConnection: (SolixSettings) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Header(
                    state = state,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                )
            }
            state.connectionError?.let { error ->
                item { ErrorCard(error) }
            }
            item { EnergyFlowCard(state.snapshot) }
            item { BatteryCard(state.snapshot) }
            item { MetricsGrid(state.snapshot) }
            item { HistoryCard(state.history) }
            item { DeviceCard(state) }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }

    if (state.showSettings) {
        SettingsDialog(
            initial = state.settings,
            testState = state.testConnectionState,
            onDismiss = onCloseSettings,
            onSave = onSaveSettings,
            onTest = onTestConnection,
        )
    }
}

@Composable
private fun Header(
    state: SolixUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("ϟ", color = MaterialTheme.colorScheme.onPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("SOLIX Monitor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                state.snapshot?.deviceModel ?: "Solarbank 4 E5000 Pro",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ConnectionPill(state)
        IconButton(onClick = onRefresh, enabled = !state.refreshing) {
            Icon(Icons.Default.Refresh, contentDescription = "Jetzt aktualisieren")
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
        }
    }
}

@Composable
private fun ConnectionPill(state: SolixUiState) {
    val connected = state.snapshot != null && state.connectionError == null
    val background = when {
        state.connectionError != null -> MaterialTheme.colorScheme.errorContainer
        connected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        state.connectionError != null -> MaterialTheme.colorScheme.onErrorContainer
        connected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(foreground, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                state.connectionError != null -> "Offline"
                state.settings.demoMode -> "Demo"
                connected -> "Lokal"
                else -> "…"
            },
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EnergyFlowCard(snapshot: SolixSnapshot?) {
    DashboardCard {
        SectionHeader(
            eyebrow = "LIVE",
            title = "Energiefluss",
            trailing = snapshot?.timestampMs?.let(::formatTime) ?: "Noch keine Daten",
        )
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
        ) {
            val pvActive = (snapshot?.pvW ?: 0) > 5
            val loadActive = (snapshot?.loadW ?: 0) > 5
            val batteryActive = abs(snapshot?.batteryW ?: 0) > 5
            val gridActive = abs(snapshot?.gridW ?: 0) > 5
            val muted = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            Canvas(Modifier.matchParentSize()) {
                val center = Offset(size.width / 2f, size.height / 2f - 12.dp.toPx())
                fun line(to: Offset, active: Boolean, color: Color) {
                    drawLine(
                        color = if (active) color else muted,
                        start = center,
                        end = to,
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                line(Offset(54.dp.toPx(), 54.dp.toPx()), pvActive, SolarAmber)
                line(Offset(size.width - 54.dp.toPx(), 54.dp.toPx()), loadActive, SolixGreen)
                line(Offset(size.width / 2f, size.height - 48.dp.toPx()), batteryActive, SolixGreen)
                line(Offset(size.width - 54.dp.toPx(), size.height - 48.dp.toPx()), gridActive, GridBlue)
            }
            FlowNode(
                modifier = Modifier.align(Alignment.TopStart),
                code = "PV",
                label = "Solar",
                value = watts(snapshot?.pvW),
                color = SolarAmber,
            )
            FlowNode(
                modifier = Modifier.align(Alignment.TopEnd),
                code = "H",
                label = "Haus",
                value = watts(snapshot?.loadW),
                color = SolixGreen,
            )
            FlowNode(
                modifier = Modifier.align(Alignment.BottomCenter),
                code = "B",
                label = when {
                    (snapshot?.batteryW ?: 0) > 10 -> "Entladung"
                    (snapshot?.batteryW ?: 0) < -10 -> "Ladung"
                    else -> "Batterie"
                },
                value = watts(snapshot?.batteryW),
                color = SolixGreen,
            )
            FlowNode(
                modifier = Modifier.align(Alignment.BottomEnd),
                code = "N",
                label = when {
                    (snapshot?.gridW ?: 0) > 10 -> "Netzbezug"
                    (snapshot?.gridW ?: 0) < -10 -> "Einspeisung"
                    else -> "Netz"
                },
                value = watts(snapshot?.gridW),
                color = GridBlue,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(58.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("ϟ", color = MaterialTheme.colorScheme.onPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FlowNode(
    modifier: Modifier,
    code: String,
    label: String,
    value: String,
    color: Color,
) {
    Column(modifier = modifier.width(108.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(54.dp).background(color.copy(alpha = 0.14f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(code, color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BatteryCard(snapshot: SolixSnapshot?) {
    DashboardCard {
        SectionHeader("SPEICHER", "Batterie", snapshot?.batteryStatus ?: "—")
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { (snapshot?.batterySoc ?: 0) / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 14.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = snapshot?.batterySoc?.toString() ?: "—",
                        fontSize = 43.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("%", modifier = Modifier.padding(bottom = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoBlock("Kapazität", snapshot?.let { "%.1f kWh".format(Locale.GERMANY, it.ratedEnergyKwh) } ?: "—")
                InfoBlock("Modus", snapshot?.operatingMode ?: "—")
            }
        }
    }
}

@Composable
private fun InfoBlock(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun MetricsGrid(snapshot: SolixSnapshot?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("PV-Erzeugung gesamt", energy(snapshot?.pvTotalKwh), SolarAmber, Modifier.weight(1f))
            MetricCard("Geladen gesamt", energy(snapshot?.chargeTotalKwh), SolixGreen, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Entladen gesamt", energy(snapshot?.dischargeTotalKwh), BatteryViolet, Modifier.weight(1f))
            MetricCard("AC-Ausgang", watts(snapshot?.acOutputW), GridBlue, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(15.dp)) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HistoryCard(history: List<HistoryPoint>) {
    DashboardCard {
        SectionHeader("LETZTE 60 MINUTEN", "Leistungsverlauf", if (history.size < 2) "Warte auf Messwerte" else "${history.size} Werte")
        Spacer(Modifier.height(14.dp))
        if (history.size < 2) {
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                Text("Der Verlauf erscheint nach den ersten Messungen.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            PowerChart(history, Modifier.fillMaxWidth().height(190.dp))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Legend("PV", SolarAmber)
                Legend("Haus", SolixGreen)
                Legend("Batterie", BatteryViolet)
                Legend("Netz", GridBlue)
            }
        }
    }
}

@Composable
private fun PowerChart(history: List<HistoryPoint>, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    Canvas(modifier) {
        val allValues = history.flatMap { listOf(it.pvW, it.loadW, it.batteryW, it.gridW) }
        val peak = ceil(max(500, allValues.maxOf { abs(it) }) / 500f) * 500f
        val hasNegative = allValues.any { it < 0 }
        val minimum = if (hasNegative) -peak else 0f
        val range = peak - minimum
        val left = 6.dp.toPx()
        val right = size.width - 6.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        repeat(5) { index ->
            val y = top + (bottom - top) * index / 4f
            drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        fun drawSeries(color: Color, value: (HistoryPoint) -> Int) {
            val path = Path()
            history.forEachIndexed { index, point ->
                val x = left + (right - left) * index / (history.size - 1).toFloat()
                val normalized = (value(point) - minimum) / range
                val y = bottom - normalized * (bottom - top)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
        }
        drawSeries(SolarAmber) { it.pvW }
        drawSeries(SolixGreen) { it.loadW }
        drawSeries(BatteryViolet) { it.batteryW }
        drawSeries(GridBlue) { it.gridW }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeviceCard(state: SolixUiState) {
    val snapshot = state.snapshot
    DashboardCard {
        SectionHeader("ANLAGE", "Gerät")
        Spacer(Modifier.height(10.dp))
        DetailRow("Modell", snapshot?.deviceModel ?: "—")
        DetailRow("Firmware", snapshot?.firmware ?: "—")
        DetailRow("Max. Laden", watts(snapshot?.maxChargeW))
        DetailRow("Max. Entladen", watts(snapshot?.maxDischargeW))
        DetailRow("Datenquelle", if (state.settings.demoMode) "Demo-Daten" else "Lokal · Modbus TCP", divider = false)
    }
}

@Composable
private fun DetailRow(label: String, value: String, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 18.dp))
    }
    if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
        content = { Column(Modifier.padding(20.dp), content = content) },
    )
}

@Composable
private fun SectionHeader(eyebrow: String, title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column {
            Text(eyebrow, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    initial: SolixSettings,
    testState: TestConnectionState,
    onDismiss: () -> Unit,
    onSave: (SolixSettings) -> Result<Unit>,
    onTest: (SolixSettings) -> Unit,
) {
    var host by remember(initial) { mutableStateOf(initial.host) }
    var port by remember(initial) { mutableStateOf(initial.port.toString()) }
    var unitId by remember(initial) { mutableStateOf(initial.unitId.toString()) }
    var refreshSeconds by remember(initial) { mutableStateOf(initial.refreshSeconds) }
    var demoMode by remember(initial) { mutableStateOf(initial.demoMode) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun candidate(): SolixSettings? {
        val parsedPort = port.toIntOrNull()
        val parsedUnit = unitId.toIntOrNull()
        if (parsedPort == null || parsedUnit == null) {
            validationError = "Port und Geräte-ID müssen Zahlen sein."
            return null
        }
        return SolixSettings(host.trim(), parsedPort, parsedUnit, refreshSeconds, demoMode)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("VERBINDUNG", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Einstellungen")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Die Daten bleiben in deinem Heimnetz. Es werden keine Anker-Zugangsdaten benötigt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Demo-Modus", fontWeight = FontWeight.SemiBold)
                        Text("Oberfläche ohne echte Anlage ausprobieren", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = demoMode, onCheckedChange = { demoMode = it; validationError = null })
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; validationError = null },
                    enabled = !demoMode,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("IP-Adresse oder Hostname") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit); validationError = null },
                        enabled = !demoMode,
                        modifier = Modifier.weight(1f),
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = unitId,
                        onValueChange = { unitId = it.filter(Char::isDigit); validationError = null },
                        enabled = !demoMode,
                        modifier = Modifier.weight(1f),
                        label = { Text("Geräte-ID") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                Text("Aktualisierung", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(listOf(2, 5, 10, 30, 60)) { seconds ->
                        FilterChip(
                            selected = refreshSeconds == seconds,
                            onClick = { refreshSeconds = seconds },
                            label = { Text("${seconds}s") },
                        )
                    }
                }
                val message = validationError ?: when (testState) {
                    TestConnectionState.Idle -> null
                    TestConnectionState.Running -> "Verbindung wird geprüft …"
                    is TestConnectionState.Success -> testState.message
                    is TestConnectionState.Failure -> testState.message
                }
                message?.let {
                    val isFailure = validationError != null || testState is TestConnectionState.Failure
                    Text(
                        text = it,
                        color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val value = candidate() ?: return@Button
                onSave(value).onFailure { validationError = it.message }
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    enabled = testState !is TestConnectionState.Running,
                    onClick = { candidate()?.let(onTest) },
                ) { Text("Testen") }
                OutlinedButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

private fun watts(value: Int?): String {
    if (value == null) return "— W"
    val absolute = abs(value.toLong())
    return if (absolute >= 1_000) {
        val decimals = if (absolute >= 10_000) 1 else 2
        "%.${decimals}f kW".format(Locale.GERMANY, absolute / 1_000.0)
    } else {
        "$absolute W"
    }
}

private fun energy(value: Double?): String {
    if (value == null) return "— kWh"
    return if (value >= 1_000) "%.2f MWh".format(Locale.GERMANY, value / 1_000.0)
    else "%.1f kWh".format(Locale.GERMANY, value)
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(timestamp))
