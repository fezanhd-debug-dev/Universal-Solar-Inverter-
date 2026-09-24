package com.solarsync.pro.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.solarsync.pro.protocols.InverterWorkMode
import com.solarsync.pro.ui.viewmodel.DiscoveryViewModel
import com.solarsync.pro.ui.viewmodel.SettingsViewModel
import com.solarsync.pro.ui.viewmodel.TemperatureUnit

/** Single-activity host: bottom nav across Dashboard, Devices, Alerts, Settings. */
class MainActivity : ComponentActivity() {

    private val discoveryViewModel: DiscoveryViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SolarSyncApp(viewModel = discoveryViewModel, settingsViewModel = settingsViewModel)
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
fun SolarSyncApp(viewModel: DiscoveryViewModel, settingsViewModel: SettingsViewModel) {
    val navController: NavHostController = rememberNavController()

    Scaffold(
        bottomBar = { SolarSyncBottomBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(viewModel, settingsViewModel) }
            composable(Screen.Devices.route) { DevicesScreen(viewModel, settingsViewModel) }
            composable(Screen.Alerts.route) { AlertsScreen(viewModel) }
            composable(Screen.Settings.route) { SettingsScreen(settingsViewModel) }
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

/** Converts a Celsius reading to the display unit chosen in Settings. */
private fun displayTemp(celsius: Double, unit: TemperatureUnit): String = when (unit) {
    TemperatureUnit.CELSIUS -> "%.1f °C".format(celsius)
    TemperatureUnit.FAHRENHEIT -> "%.1f °F".format(celsius * 9.0 / 5.0 + 32.0)
}

@Composable
private fun DashboardScreen(viewModel: DiscoveryViewModel, settingsViewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val settings by settingsViewModel.state.collectAsState()

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
            TelemetryCard("Inverter Temp", displayTemp(telemetry.inverterTempCelsius, settings.temperatureUnit))
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

/** Reads the phone's current WiFi IP and returns the first three octets ("192.168.1"), or null if unavailable. */
private fun detectSubnetPrefix(context: Context): String? = try {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
    if (ipInt == 0) {
        null
    } else {
        // WifiManager reports the IP in little-endian order.
        "%d.%d.%d".format(ipInt and 0xFF, (ipInt shr 8) and 0xFF, (ipInt shr 16) and 0xFF)
    }
} catch (e: Exception) {
    null
}

@Composable
private fun DevicesScreen(viewModel: DiscoveryViewModel, settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val settings by settingsViewModel.state.collectAsState()

    // --- WiFi/LAN permission + subnet auto-detect ---
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }

    var subnet by remember { mutableStateOf("192.168.1") }
    var autoDetectAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && !autoDetectAttempted) {
            autoDetectAttempted = true
            detectSubnetPrefix(context)?.let { detected -> subnet = detected }
        }
    }

    // --- Bluetooth permission (Android 12+ needs BLUETOOTH_SCAN + BLUETOOTH_CONNECT explicitly) ---
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList() // pre-12: BLUETOOTH/BLUETOOTH_ADMIN are normal (install-time) permissions
    }
    var hasBluetoothPermission by remember {
        mutableStateOf(
            bluetoothPermissions.isEmpty() ||
                bluetoothPermissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
        )
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasBluetoothPermission = results.values.all { it } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ===================== WiFi / LAN section =====================
        Text("Discover Inverter (WiFi)", style = MaterialTheme.typography.headlineSmall)

        if (!hasLocationPermission) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Location permission lets the app read your phone's WiFi network so it can " +
                            "auto-fill the right subnet and scan for your inverter."
                    )
                    Button(onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                        Text("Grant Permission")
                    }
                }
            }
        }

        OutlinedTextField(
            value = subnet,
            onValueChange = { subnet = it },
            label = { Text("Subnet prefix (e.g. 192.168.1)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                viewModel.startDiscovery(
                    subnetPrefix = subnet,
                    pollingIntervalSeconds = settings.pollingIntervalSeconds,
                    solisCredentials = if (settings.solisCloudKeyId.isNotBlank()) {
                        DiscoveryViewModel.SolisCloudCredentials(
                            keyId = settings.solisCloudKeyId,
                            keySecret = settings.solisCloudKeySecret,
                            inverterSerial = settings.solisInverterSerial
                        )
                    } else {
                        null
                    }
                )
            }
        ) {
            Text("Scan Network")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ===================== Bluetooth section =====================
        Text("Discover Inverter (Bluetooth)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "For Renogy, EPEVER and SRNE controllers paired with a BLE module.",
            style = MaterialTheme.typography.bodySmall
        )

        if (!hasBluetoothPermission) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bluetooth permission is needed to scan for and connect to your controller's BLE module.")
                    Button(onClick = { bluetoothPermissionLauncher.launch(bluetoothPermissions.toTypedArray()) }) {
                        Text("Grant Permission")
                    }
                }
            }
        } else {
            Button(onClick = { viewModel.startBleScan(context, settings.pollingIntervalSeconds) }) {
                Text("Scan Bluetooth")
            }
        }

        when (val discovery = state.discovery) {
            is DiscoveryViewModel.DiscoveryState.Idle -> Text("Not scanned yet.")
            is DiscoveryViewModel.DiscoveryState.Scanning -> Text("Scanning…")
            is DiscoveryViewModel.DiscoveryState.DeviceFound ->
                Text("Found ${discovery.protocol} at ${discovery.ip}")
            is DiscoveryViewModel.DiscoveryState.NotFound ->
                Text(
                    "No inverter found on that subnet. Make sure your phone is on the same WiFi " +
                        "network as the inverter's dongle, and that the subnet prefix above matches " +
                        "it (check the dongle's own app or its IP label if unsure)."
                )
            is DiscoveryViewModel.DiscoveryState.BleNotFound ->
                Text(
                    "No Renogy/EPEVER/SRNE BLE module found nearby. Make sure the controller's " +
                        "Bluetooth module is powered and not already connected to another phone/app."
                )
            is DiscoveryViewModel.DiscoveryState.BleDevicesFound ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Found ${discovery.devices.size} device(s) — tap one to connect:")
                    discovery.devices.forEach { device ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .clickable { viewModel.connectToBleDevice(context, device) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                                    Text(device.brand.name, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Connect")
                            }
                        }
                    }
                }
            is DiscoveryViewModel.DiscoveryState.BleConnected ->
                Text("Connected to ${discovery.device.name} (${discovery.device.brand})")
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
private fun SettingsScreen(settingsViewModel: SettingsViewModel) {
    val settings by settingsViewModel.state.collectAsState()

    var pollingIntervalText by remember(settings.pollingIntervalSeconds) {
        mutableStateOf(settings.pollingIntervalSeconds.toString())
    }
    var keyId by remember(settings.solisCloudKeyId) { mutableStateOf(settings.solisCloudKeyId) }
    var keySecret by remember(settings.solisCloudKeySecret) { mutableStateOf(settings.solisCloudKeySecret) }
    var serial by remember(settings.solisInverterSerial) { mutableStateOf(settings.solisInverterSerial) }
    var savedJustNow by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Polling Interval", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = pollingIntervalText,
                onValueChange = { pollingIntervalText = it.filter { c -> c.isDigit() } },
                label = { Text("Seconds between telemetry reads (1–60)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Temperature Unit", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.temperatureUnit == TemperatureUnit.CELSIUS,
                    onClick = { settingsViewModel.updateTemperatureUnit(TemperatureUnit.CELSIUS) }
                )
                Text("Celsius (°C)", modifier = Modifier.padding(end = 16.dp))
                RadioButton(
                    selected = settings.temperatureUnit == TemperatureUnit.FAHRENHEIT,
                    onClick = { settingsViewModel.updateTemperatureUnit(TemperatureUnit.FAHRENHEIT) }
                )
                Text("Fahrenheit (°F)")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SolisCloud Fallback (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Only needed if your Solis inverter isn't reachable on the local network. " +
                    "Get these from the SolisCloud web portal's API settings.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = keyId,
                onValueChange = { keyId = it },
                label = { Text("Key ID") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = keySecret,
                onValueChange = { keySecret = it },
                label = { Text("Key Secret") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = serial,
                onValueChange = { serial = it },
                label = { Text("Inverter Serial Number") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Button(
            onClick = {
                settingsViewModel.updatePollingInterval(pollingIntervalText.toIntOrNull() ?: 2)
                settingsViewModel.updateSolisCredentials(keyId, keySecret, serial)
                settingsViewModel.save()
                savedJustNow = true
            }
        ) {
            Text("Save Settings")
        }

        if (savedJustNow) {
            Text("Saved.", color = MaterialTheme.colorScheme.primary)
        }
    }
}
