package com.solarsync.pro.protocols

import com.solarsync.pro.core.network.ModbusPacketCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Adapter for Deye hybrid inverters and Knox-branded dongles (Krypton, Argon
 * series) that use the Solarman/IGEN V5 transport over TCP port 8899,
 * carrying a standard Modbus RTU PDU as its payload.
 */
class DeyeKnoxProtocolAdapter(
    private val ip: String,
    private val loggerSerial: Long, // printed on the dongle sticker
    private val port: Int = 8899
) : InverterAdapter {

    // Common Deye/Knox holding register map (varies slightly by firmware)
    private object Registers {
        const val BATTERY_SOC = 588
        const val BATTERY_POWER = 590
        const val PV_POWER_TOTAL = 672
        const val GRID_POWER = 625
        const val LOAD_POWER = 653
        const val INVERTER_TEMP = 541
    }

    override suspend fun readTelemetry(): TelemetrySnapshot = withContext(Dispatchers.IO) {
        val regs = readRegisters(startAddress = 588, quantity = 90)

        fun reg(offsetFromStart: Int): Int = regs.getOrElse(offsetFromStart) { 0 }
        fun signed(v: Int): Int = if (v > 32767) v - 65536 else v

        TelemetrySnapshot(
            batterySocPercent = reg(Registers.BATTERY_SOC - 588),
            batteryPowerWatts = signed(reg(Registers.BATTERY_POWER - 588)),
            pvPowerWatts = reg(Registers.PV_POWER_TOTAL - 588),
            gridPowerWatts = signed(reg(Registers.GRID_POWER - 588)),
            loadPowerWatts = reg(Registers.LOAD_POWER - 588),
            inverterTempCelsius = signed(reg(Registers.INVERTER_TEMP - 588)) / 10.0
        )
    }

    override suspend fun setWorkMode(mode: InverterWorkMode): Boolean = withContext(Dispatchers.IO) {
        // Register 141 on most Deye hybrids: 0=Selling first, 1=Zero-export, 2=Limited
        val value = when (mode) {
            InverterWorkMode.SELF_USE -> 0
            InverterWorkMode.BATTERY_PRIORITY -> 1
            InverterWorkMode.GRID_PRIORITY -> 2
        }
        writeSingleRegister(address = 141, value = value)
    }

    /** Reads `quantity` holding registers starting at `startAddress` via a Solarman V5 frame. */
    private suspend fun readRegisters(startAddress: Int, quantity: Int): IntArray =
        withContext(Dispatchers.IO) {
            val readPdu = byteArrayOf(
                0x01, // unit id, most Deye/Knox loggers respond regardless of unit id
                0x03, // function code: Read Holding Registers
                (startAddress shr 8).toByte(), (startAddress and 0xFF).toByte(),
                (quantity shr 8).toByte(), (quantity and 0xFF).toByte()
            )
            val rtuFrame = ModbusPacketCodec.appendCrc(readPdu)
            val solarmanFrame = ModbusPacketCodec.buildSolarmanV5Frame(loggerSerial, rtuFrame)

            val responseFrame = sendAndReceive(solarmanFrame)
            val modbusResponse = ModbusPacketCodec.unwrapSolarmanV5Frame(responseFrame)

            // modbusResponse: [unitId][funcCode][byteCount][data...][crc(2)]
            val byteCount = modbusResponse[2].toInt() and 0xFF
            val registerCount = byteCount / 2
            IntArray(registerCount) { i ->
                val hi = modbusResponse[3 + i * 2].toInt() and 0xFF
                val lo = modbusResponse[4 + i * 2].toInt() and 0xFF
                (hi shl 8) or lo
            }
        }

    private suspend fun writeSingleRegister(address: Int, value: Int): Boolean =
        withContext(Dispatchers.IO) {
            val writePdu = byteArrayOf(
                0x01,
                0x06, // function code: Write Single Register
                (address shr 8).toByte(), (address and 0xFF).toByte(),
                (value shr 8).toByte(), (value and 0xFF).toByte()
            )
            val rtuFrame = ModbusPacketCodec.appendCrc(writePdu)
            val solarmanFrame = ModbusPacketCodec.buildSolarmanV5Frame(loggerSerial, rtuFrame)
            try {
                sendAndReceive(solarmanFrame)
                true
            } catch (e: Exception) {
                false
            }
        }

    private fun sendAndReceive(frame: ByteArray, timeoutMs: Int = 3000): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val out = DataOutputStream(socket.getOutputStream())
            out.write(frame)
            out.flush()

            val input = DataInputStream(socket.getInputStream())
            // Read available response — Solarman frames are short (<200 bytes typical)
            val buffer = ByteArray(512)
            val read = input.read(buffer)
            require(read > 0) { "Empty response from Knox/Deye dongle at $ip:$port" }
            return buffer.copyOfRange(0, read)
        }
    }
}
