package com.solarsync.pro.protocols

import android.content.Context
import com.solarsync.pro.core.network.BleGattTransport
import com.solarsync.pro.core.network.ModbusPacketCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Adapter for SRNE hybrid inverters and MPPT charge controllers paired with
 * SRNE's BT-2 Bluetooth module — the same OEM module family used by several
 * other budget solar brands, which is why it shares Renogy's BT-2 UUID
 * scheme. If your specific SRNE dongle uses a different module, re-check
 * UUIDs with a generic BLE scanner (e.g. nRF Connect).
 *
 * Register map follows SRNE's published Modbus map for its HF/ASF-series
 * hybrid inverters.
 */
class SrneBleAdapter(
    context: Context,
    macAddress: String,
    private val unitId: Int = 1
) : InverterAdapter {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb")
        val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffd1-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME_PREFIX = "BT-2" // SRNE's BT-2 module commonly advertises as "BT-2-xxxxxx"

        private const val REG_BATTERY_SOC = 0x0100
        private const val REG_BATTERY_VOLTAGE = 0x0101   // 0.1 V
        private const val REG_BATTERY_CURRENT = 0x0102     // 0.1 A, signed (charge +, discharge -)
        private const val REG_PV_POWER = 0x0107              // W
        private const val REG_LOAD_POWER = 0x010C              // W
        private const val REG_GRID_POWER = 0x0110                // W (hybrid inverter models only)
        private const val REG_INVERTER_TEMP = 0x0115                // signed °C

        // Work-mode control register — 0=self-use, 1=grid-priority(mains), 2=battery-priority(SBU).
        private const val REG_WORK_MODE = 0x2000
    }

    private val transport = BleGattTransport(
        context = context,
        macAddress = macAddress,
        writeCharacteristicUuid = WRITE_CHARACTERISTIC_UUID,
        notifyCharacteristicUuid = NOTIFY_CHARACTERISTIC_UUID
    )

    override suspend fun readTelemetry(): TelemetrySnapshot = withContext(Dispatchers.IO) {
        val regs = readRegisters(startAddress = REG_BATTERY_SOC, quantity = 0x16)

        fun reg(address: Int): Int = regs.getOrElse(address - REG_BATTERY_SOC) { 0 }
        fun signed(v: Int): Int = if (v > 32767) v - 65536 else v

        val batteryVoltage = reg(REG_BATTERY_VOLTAGE) / 10.0
        val batteryCurrent = signed(reg(REG_BATTERY_CURRENT)) / 10.0
        val batteryPowerWatts = (batteryVoltage * batteryCurrent).toInt()

        TelemetrySnapshot(
            batterySocPercent = reg(REG_BATTERY_SOC),
            batteryPowerWatts = batteryPowerWatts,
            pvPowerWatts = reg(REG_PV_POWER),
            gridPowerWatts = signed(reg(REG_GRID_POWER)),
            loadPowerWatts = reg(REG_LOAD_POWER),
            inverterTempCelsius = signed(reg(REG_INVERTER_TEMP)).toDouble()
        )
    }

    override suspend fun setWorkMode(mode: InverterWorkMode): Boolean = withContext(Dispatchers.IO) {
        val value = when (mode) {
            InverterWorkMode.SELF_USE -> 0
            InverterWorkMode.GRID_PRIORITY -> 1
            InverterWorkMode.BATTERY_PRIORITY -> 2
        }
        try {
            val pdu = byteArrayOf(
                unitId.toByte(),
                0x06, // Write Single Register
                (REG_WORK_MODE shr 8).toByte(), (REG_WORK_MODE and 0xFF).toByte(),
                (value shr 8).toByte(), (value and 0xFF).toByte()
            )
            val frame = ModbusPacketCodec.appendCrc(pdu)
            transport.writeAndAwaitResponse(frame)
            true
        } catch (e: Exception) {
            false
        }
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
