package com.solarsync.pro.protocols

/**
 * Common contract every brand adapter (Deye/Knox, Inverex/Voltronic,
 * Solis, Fronius/SolaX) implements, so DiscoveryViewModel and the UI
 * layer can talk to any inverter through one interface.
 */
interface InverterAdapter {
    suspend fun readTelemetry(): TelemetrySnapshot
    suspend fun setWorkMode(mode: InverterWorkMode): Boolean
}

data class TelemetrySnapshot(
    val batterySocPercent: Int,
    val batteryPowerWatts: Int,   // positive = charging, negative = discharging
    val pvPowerWatts: Int,
    val gridPowerWatts: Int,      // positive = importing, negative = exporting
    val loadPowerWatts: Int,
    val inverterTempCelsius: Double
)

enum class InverterWorkMode {
    SELF_USE,
    BATTERY_PRIORITY,
    GRID_PRIORITY
}
