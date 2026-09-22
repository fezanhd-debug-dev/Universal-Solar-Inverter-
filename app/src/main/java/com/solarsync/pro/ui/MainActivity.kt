package com.solarsync.pro.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.solarsync.pro.protocols.InverterWorkMode
import com.solarsync.pro.ui.viewmodel.DiscoveryViewModel

/** Single-activity host: bottom nav across Dashboard, Devices, Alerts, Settings. */
class MainActivity : ComponentActivity() {

    private val discoveryViewModel: DiscoveryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SolarSyncApp(viewModel = discoveryViewModel)
            }
        }
    }
}

private sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Filled.Home)
    data object Devices : Screen("devices", "Devices", Icons.Filled.Wifi)
    data object Alerts : Screen("alerts", "Alerts", Icons.Filled.Notifications)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

private val bottomNavScreens = listOf(Screen.Dashboard, Screen.Devices, Screen.Alerts, Screen.Settings)

@Composable
fun SolarSyncApp(viewModel: DiscoveryViewModel) {
    val navController: NavHostController = rememberNavController()

    Scaffold(
        bottomBar = { SolarSyncBottomBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(viewModel) }
            composable(Screen.Devices.route) { DevicesScreen(viewModel) }
            composable(Screen.Alerts.route) { AlertsScreen(viewModel) }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}

@Composable
private fun SolarSyncBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        bottomNavScreens.forEach { screen ->
            NavigationBarItem(
                selected = currentRoute == screen.route,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(screen.icon, contentDescription = screen.label) },
                label = { Text(screen.label) }
            )
        }
    }
}

@Composable
private fun DashboardScreen(viewModel: DiscoveryViewModel) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Live Telemetry", style = MaterialTheme.typography.headlineSmall)

        val telemetry = state.telemetry
        if (telemetry == null) {
            Text("Waiting for data — scan for a device on the Devices tab.")
        } else {
            TelemetryCard("Battery", "${telemetry.batterySocPercent}%  (${telemetry.batteryPowerWatts} W)")
            TelemetryCard("Solar (PV)", "${telemetry.pvPowerWatts} W")
            TelemetryCard("Grid", "${telemetry.gridPowerWatts} W")
            TelemetryCard("Load", "${telemetry.loadPowerWatts} W")
            TelemetryCard("Inverter Temp", "${telemetry.inverterTempCelsius} °C")
        }

        state.lastError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.dispatchWorkModeChange(InverterWorkMode.SELF_USE) }) {
                Text("Self Use")
            }
            Button(onClick = { viewModel.dispatchWorkModeChange(InverterWorkMode.BATTERY_PRIORITY) }) {
                Text("Battery Priority")
            }
        }
    }
}

@Composable
private fun TelemetryCard(label: String, value: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun DevicesScreen(viewModel: DiscoveryViewModel) {
    var subnet by remember { mutableStateOf("192.168.1") }
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Discover Inverter", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = subnet,
            onValueChange = { subnet = it },
            label = { Text("Subnet prefix (e.g. 192.168.1)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { viewModel.startDiscovery(subnet) }) {
            Text("Scan Network")
        }

        when (val discovery = state.discovery) {
            is DiscoveryViewModel.DiscoveryState.Idle -> Text("Not scanned yet.")
            is DiscoveryViewModel.DiscoveryState.Scanning -> Text("Scanning…")
            is DiscoveryViewModel.DiscoveryState.DeviceFound ->
                Text("Found ${discovery.protocol} at ${discovery.ip}")
            is DiscoveryViewModel.DiscoveryState.NotFound ->
                Text("No inverter found on that subnet.")
        }
    }
}

@Composable
private fun AlertsScreen(viewModel: DiscoveryViewModel) {
    val state by viewModel.uiState.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Alerts", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        state.lastError?.let { Text("⚠️ $it") } ?: Text("No active alerts.")
    }
}

@Composable
private fun SettingsScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("Polling interval, units, and cloud API keys go here.")
    }
}
