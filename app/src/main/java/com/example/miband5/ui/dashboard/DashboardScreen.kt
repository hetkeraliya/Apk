package com.example.miband5.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.miband5.ble.BleConnectionState
import com.example.miband5.data.AuthKeyStore
import com.example.miband5.ui.components.BellCurveChart
import com.example.miband5.ui.components.DaySelector
import com.example.miband5.ui.components.GlassCard
import com.example.miband5.ui.components.StatPill
import com.example.miband5.ui.theme.HeartRateStops
import com.example.miband5.ui.theme.SleepStops
import com.example.miband5.ui.theme.StepsStops
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    connectionViewModel: ConnectionViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val connState by connectionViewModel.state.collectAsState()
    val context = LocalContext.current

    var showAuthDialog by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) connectionViewModel.startScan()
    }

    fun requestPermsAndScan() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) connectionViewModel.startScan()
        else permLauncher.launch(missing.toTypedArray())
    }

    // Keep the dashboard live while the background service syncs.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(10_000)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Header(connState) {
                if (AuthKeyStore(context).authKey == null) showAuthDialog = true
                else requestPermsAndScan()
            }
            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is DashboardUiState.Loading -> Text("Loading…", color = MaterialTheme.colorScheme.onBackground)
                is DashboardUiState.Ready -> {
                    ChartCard(s, viewModel)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatPill(
                            title = "Steps",
                            value = s.days[viewModel.selectedIndex].steps.toString(),
                            unit = "steps",
                            changePct = changePct(s, viewModel.selectedIndex) { it.steps.toFloat() },
                            colorStops = StepsStops,
                            modifier = Modifier.weight(1f)
                        )
                        StatPill(
                            title = "Heart Rate",
                            value = (s.days[viewModel.selectedIndex].hrAvg ?: 0).toString(),
                            unit = "bpm",
                            changePct = changePct(s, viewModel.selectedIndex) { (it.hrAvg ?: 0).toFloat() },
                            colorStops = HeartRateStops,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatPill(
                            title = "Sleep",
                            value = s.days[viewModel.selectedIndex].sleep.toString(),
                            unit = "min",
                            changePct = changePct(s, viewModel.selectedIndex) { it.sleep.toFloat() },
                            colorStops = SleepStops,
                            modifier = Modifier.weight(1f)
                        )
                        AddWidgetButton(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            title = { Text("Auth key") },
            text = {
                Column {
                    Text("Paste the 32-hex-char key from Zepp / Gadgetbridge / huami-token.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        singleLine = true,
                        label = { Text("32 hex chars") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (keyText.trim().length == 32) {
                        AuthKeyStore(context).authKey = keyText.trim().hexToBytes()
                        showAuthDialog = false
                        requestPermsAndScan()
                    }
                }) { Text("Save & Sync") }
            },
            dismissButton = {
                TextButton(onClick = { showAuthDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Header(connState: BleConnectionState, onSync: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Today",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
        val dotColor = when (connState) {
            is BleConnectionState.Connected -> Color(0xFF4CD964)
            is BleConnectionState.Scanning,
            is BleConnectionState.Connecting,
            is BleConnectionState.Authenticating,
            is BleConnectionState.DiscoveringServices -> Color(0xFFFFCC00)
            is BleConnectionState.Error -> Color(0xFFFF3B30)
            else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.size(10.dp))
        Button(onClick = onSync, shape = RoundedCornerShape(999.dp)) { Text("Sync") }
    }
}

@Composable
private fun ChartCard(state: DashboardUiState.Ready, viewModel: DashboardViewModel) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row {
            listOf("Steps", "Heart Rate", "Sleep").forEachIndexed { i, label ->
                val selected = viewModel.selectedTab == i
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { viewModel.selectTab(i) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        label,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val values = when (viewModel.selectedTab) {
            1 -> state.days.map { (it.hrAvg ?: 0).toFloat() }
            2 -> state.days.map { it.sleep.toFloat() }
            else -> state.days.map { it.steps.toFloat() }
        }
        BellCurveChart(values = values, selectedIndex = viewModel.selectedIndex)
        Spacer(Modifier.height(16.dp))
        DaySelector(
            days = state.days,
            selectedIndex = viewModel.selectedIndex,
            onSelect = viewModel::selectDay
        )
    }
}

@Composable
private fun AddWidgetButton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(104.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White)
            .clickable { /* widget picker — future phase */ },
        contentAlignment = Alignment.Center
    ) {
        Text("+", color = Color(0xFF14151F), fontSize = 28.sp, fontWeight = FontWeight.Light)
    }
}

private fun changePct(
    state: DashboardUiState.Ready,
    index: Int,
    value: (DayData) -> Float
): Float? {
    if (index <= 0) return null
    val cur = value(state.days[index])
    val prev = value(state.days[index - 1])
    if (prev == 0f) return null
    return (cur - prev) / prev * 100f
}

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
