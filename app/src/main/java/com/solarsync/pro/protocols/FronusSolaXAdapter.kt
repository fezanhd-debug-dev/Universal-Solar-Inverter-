package com.solarsync.pro.protocols

import com.solarsync.pro.core.network.ModbusPacketCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapter for Fronius, SolaX, and Growatt inverters that implement the
 * SunSpec standard Modbus model over TCP port 502. SunSpec models are
 * self-describing (base address 40000 = "SunS" marker, followed by a
 * chain of model blocks), but for a bounded read we target the common
 * Model 103 (three-phase inverter) + Model 802 (battery storage) offsets
 * used by these three brands' default SunSpec maps.
 */
class FronusSolaXAdapter(
    private val ip: String,
    private val port: Int = 502,
    private val unitId: Int = 1
) : InverterAdapter {

    private object SunSpecBase {
        const val MARKER = 40000
        // Model 103 (inverter, float) commonly starts here on Fronius/SolaX/Growatt defaults
        const val INVERTER_MODEL = 40070
        // Model 802 (battery) typical offset on units that expose storage
        const val BATTERY_MODEL = 40252
    }

    override suspend fun readTelemetry(): TelemetrySnapshot = withContext(Dispatchers.IO) {
        val inverterRegs = ModbusPacketCodec.readHoldingRegistersTcp(
            ip = ip, port = port, unitId = unitId,
            startAddress = SunSpecBase.INVERTER_MODEL, quantity = 50
        )

        val batteryRegs = try {
            ModbusPacketCodec.readHoldingRegistersTcp(
                ip = ip, port = port, unitId = unitId,
                startAddress = SunSpecBase.BATTERY_MODEL, quantity = 40
            )
        } catch (e: Exception) {
            IntArray(0) // battery model absent on grid-tie-only units (e.g. base Fronius Primo)
        }

        fun signed(v: Int): Int = if (v > 32767) v - 65536 else v

        // Model 103 offsets (relative to model start, after the 2-word model header):
        //   +2 = AC Power (W), scale factor at +5 (typically -1..0)
        val acPowerRaw = signed(inverterRegs.getOrElse(2) { 0 })
        val acPowerScale = signed(inverterRegs.getOrElse(5) { 0 })
        val pvPower = scaledValue(acPowerRaw, acPowerScale)

        // Model 802 offsets: +3 = SOC (%), +5 = InstantaneousPower (W, signed)
        val soc = batteryRegs.getOrElse(3) { 0 }
        val batteryPowerRaw = signed(batteryRegs.getOrElse(5) { 0 })

        TelemetrySnapshot(
            batterySocPercent = soc,
            batteryPowerWatts = batteryPowerRaw,
            pvPowerWatts = pvPower.coerceAtLeast(0),
            gridPowerWatts = 0, // requires Model 203 (meter) which isn't always present
            loadPowerWatts = 0, // SunSpec has no universal "load" register; brand-specific
            inverterTempCelsius = 0.0
        )
    }

    override suspend fun setWorkMode(mode: InverterWorkMode): Boolean = withContext(Dispatchers.IO) {
        // SunSpec's storage control block (Model 124) varies significantly by brand
        // firmware; most SolaX/Growatt units expose a simpler vendor register instead.
        // Left as a controlled no-op here — safe default until a brand-specific
        // control register is confirmed against the connected unit's model map.
        false
    }

    private fun scaledValue(raw: Int, scaleFactor: Int): Int =
        (raw * Math.pow(10.0, scaleFactor.toDouble())).toInt()
}
