package com.solarsync.pro.protocols

import android.content.Context
import com.solarsync.pro.core.network.BleGattTransport
import com.solarsync.pro.core.network.ModbusPacketCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Adapter for Renogy charge controllers / inverters paired with a Renogy
 * BT-1 or BT-2 Bluetooth module. These modules proxy standard Modbus RTU
 * frames over a BLE write characteristic (commands in) and notify
 * characteristic (responses out) — the UUIDs below are the commonly
 * reported values for Renogy's official BT modules.
 *
 * Register map follows Renogy's published Modbus map for its Rover/Wanderer
 * charge controllers and hybrid inverters; some models offset a few
 * registers, so treat these as a solid starting point rather than gospel.
 */
class RenogyBleAdapter(
    context: Context,
    macAddress: String,
    private val unitId: Int = 0xFF // Renogy modules commonly respond to broadcast/any unit id
) : InverterAdapter {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb")
        val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffd1-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME_PREFIX = "BT-TH" // Renogy BT-1/BT-2 modules typically advertise as "BT-TH-xxxxxx"

        private const val REG_BATTERY_SOC = 0x100
        private const val REG_BATTERY_VOLTAGE = 0x101       // 0.1 V
        private const val REG_BATTERY_CHARGE_CURRENT = 0x102 // 0.01 A
        private const val REG_TEMPERATURES = 0x103           // hi=controller temp, lo=battery temp (signed °C)
        private const val REG_LOAD_POWER = 0x106              // W
        private const val REG_PV_VOLTAGE = 0x107              // 0.1 V
        private const val REG_PV_CURRENT = 0x108               // 0.01 A
    }

    private val transport = BleGattTransport(
        context = context,
        macAddress = macAddress,
        writeCharacteristicUuid = WRITE_CHARACTERISTIC_UUID,
        notifyCharacteristicUuid = NOTIFY_CHARACTERISTIC_UUID
    )

    override suspend fun readTelemetry(): TelemetrySnapshot = withContext(Dispatchers.IO) {
        val regs = readRegisters(startAddress = REG_BATTERY_SOC, quantity = 9)

        fun reg(address: Int): Int = regs.getOrElse(address - REG_BATTERY_SOC) { 0 }
        fun signedByte(v: Int): Int = if (v > 127) v - 256 else v

        val batterySoc = reg(REG_BATTERY_SOC)
        val batteryVoltage = reg(REG_BATTERY_VOLTAGE) / 10.0
        val chargeCurrent = reg(REG_BATTERY_CHARGE_CURRENT) / 100.0
        val batteryPowerWatts = (batteryVoltage * chargeCurrent).toInt()

        val tempReg = reg(REG_TEMPERATURES)
        val controllerTempC = signedByte((tempReg shr 8) and 0xFF)

        val pvVoltage = reg(REG_PV_VOLTAGE) / 10.0
        val pvCurrent = reg(REG_PV_CURRENT) / 100.0
        val pvPowerWatts = (pvVoltage * pvCurrent).toInt()

        TelemetrySnapshot(
            batterySocPercent = batterySoc,
            batteryPowerWatts = batteryPowerWatts,
            pvPowerWatts = pvPowerWatts,
            gridPowerWatts = 0, // Renogy charge controllers are off-grid devices; no grid register
            loadPowerWatts = reg(REG_LOAD_POWER),
            inverterTempCelsius = controllerTempC.toDouble()
        )
    }

    override suspend fun setWorkMode(mode: InverterWorkMode): Boolean = withContext(Dispatchers.IO) {
        // Renogy Rover/Wanderer controllers don't expose a "work mode" register the way
        // grid-tied hybrids do (self-use/battery-priority/grid-priority is a hybrid-inverter
        // concept) — left as a safe no-op until a specific controllable register is confirmed.
        false
    }

    private suspend fun readRegisters(startAddress: Int, quantity: Int): IntArray {
        val pdu = byteArrayOf(
            unitId.toByte(),
            0x03,
            (startAddress shr 8).toByte(), (startAddress and 0xFF).toByte(),
            (quantity shr 8).toByte(), (quantity and 0xFF).toByte()
        )
        val frame = ModbusPacketCodec.appendCrc(pdu)
        val response = transport.writeAndAwaitResponse(frame)
        return ModbusPacketCodec.parseReadRegistersRtuResponse(response)
    }
}
