package com.solarsync.pro.protocols

import android.content.Context
import com.solarsync.pro.core.network.BleGattTransport
import com.solarsync.pro.core.network.ModbusPacketCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Adapter for EPEVER charge controllers (Tracer/Tracer-AN series) paired
 * with EPEVER's BLE dongle. These dongles are commonly built on an HM-10
 * style BLE-UART bridge module — a single characteristic is used for both
 * writing commands and receiving notifications — which then carries the
 * same Modbus RTU protocol EPEVER's RS485/USB adapters use.
 *
 * Register map follows EPEVER's published Modbus real-time data block for
 * Tracer-AN series controllers; older/other series may shift a few offsets.
 */
class EpeverBleAdapter(
    context: Context,
    macAddress: String,
    private val unitId: Int = 1
) : InverterAdapter {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        // Same characteristic UUID is used for both write and notify on this module type.
        val UART_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME_PREFIX = "EPEVER" // adjust to match your dongle's advertised name if different

        private const val REG_PV_VOLTAGE = 0x3100       // 0.01 V
        private const val REG_PV_CURRENT = 0x3101         // 0.01 A
        private const val REG_PV_POWER_LOW = 0x3102        // 0.01 W (32-bit: low word)
        private const val REG_BATTERY_VOLTAGE = 0x3104      // 0.01 V
        private const val REG_LOAD_CURRENT = 0x310D           // 0.01 A
        private const val REG_LOAD_POWER_LOW = 0x310E           // 0.01 W (32-bit: low word)
        private const val REG_BATTERY_TEMP = 0x3110               // 0.01 °C, offset -20
        private const val REG_BATTERY_SOC = 0x311A                 // %
    }

    private val transport = BleGattTransport(
        context = context,
        macAddress = macAddress,
        writeCharacteristicUuid = UART_CHARACTERISTIC_UUID,
        notifyCharacteristicUuid = UART_CHARACTERISTIC_UUID
    )

    override suspend fun readTelemetry(): TelemetrySnapshot = withContext(Dispatchers.IO) {
        // Registers span a wide range (0x3100-0x311A); read the full block in one request.
        val regs = readRegisters(startAddress = REG_PV_VOLTAGE, quantity = 0x1B)

        fun reg(address: Int): Int = regs.getOrElse(address - REG_PV_VOLTAGE) { 0 }

        val pvVoltage = reg(REG_PV_VOLTAGE) / 100.0
        val pvCurrent = reg(REG_PV_CURRENT) / 100.0
        val pvPowerWatts = (pvVoltage * pvCurrent).toInt()

        val loadCurrent = reg(REG_LOAD_CURRENT) / 100.0
        val batteryVoltage = reg(REG_BATTERY_VOLTAGE) / 100.0
        val loadPowerWatts = (loadCurrent * batteryVoltage).toInt()

        val batteryTempRaw = reg(REG_BATTERY_TEMP)
        val batteryTempC = (batteryTempRaw / 100.0) - 20.0

        TelemetrySnapshot(
            batterySocPercent = reg(REG_BATTERY_SOC),
            batteryPowerWatts = 0, // EPEVER's map splits charge/discharge into separate registers not read here
            pvPowerWatts = pvPowerWatts,
            gridPowerWatts = 0, // off-grid charge controller — no grid register
            loadPowerWatts = loadPowerWatts,
            inverterTempCelsius = batteryTempC
        )
    }

    override suspend fun setWorkMode(mode: InverterWorkMode): Boolean = withContext(Dispatchers.IO) {
        // EPEVER charge controllers don't expose a grid-tied "work mode" concept.
        false
    }

    private suspend fun readRegisters(startAddress: Int, quantity: Int): IntArray {
        val pdu = byteArrayOf(
            unitId.toByte(),
            0x04, // EPEVER real-time data is read via function code 0x04 (Read Input Registers)
            (startAddress shr 8).toByte(), (startAddress and 0xFF).toByte(),
            (quantity shr 8).toByte(), (quantity and 0xFF).toByte()
        )
        val frame = ModbusPacketCodec.appendCrc(pdu)
        val response = transport.writeAndAwaitResponse(frame)
        return ModbusPacketCodec.parseReadRegistersRtuResponse(response)
    }
}
