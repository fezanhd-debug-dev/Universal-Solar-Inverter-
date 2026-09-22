package com.solarsync.pro.protocols

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Adapter for Inverex Nitrox/Aerox and Voltronic Axpert-family inverters.
 * These speak the "PI30" ASCII command set over a WiFi-to-RS232 bridge
 * (typically TCP port 5000, sometimes telnet-style port 23).
 *
 * PI30 commands are plain ASCII strings terminated by a CRC16 + <CR>,
 * e.g. "QPIGS" (general status query) or "QPIRI" (rated info query).
 */
class VoltronicInverexAdapter(
    private val ip: String,
    private val port: Int = 5000
) : InverterAdapter {

    override suspend fun readTelemetry(): TelemetrySnapshot = withContext(Dispatchers.IO) {
        val response = sendCommand("QPIGS")
        parseQpigs(response)
    }

    override suspend fun setWorkMode(mode: InverterWorkMode): Boolean = withContext(Dispatchers.IO) {
        // POP<n>: 00 = Utility first, 01 = Solar first, 02 = SBU (battery priority)
        val code = when (mode) {
            InverterWorkMode.GRID_PRIORITY -> "00"
            InverterWorkMode.SELF_USE -> "01"
            InverterWorkMode.BATTERY_PRIORITY -> "02"
        }
        val ack = sendCommand("POP$code")
        ack.startsWith("(ACK")
    }

    /**
     * QPIGS reply layout (space-separated, values in order):
     * grid V, grid Hz, out V, out Hz, load VA, load W, load%, bus V,
     * battery V, battery charge A, battery SOC%, temp C, PV1 A, PV1 V,
     * battery V (scc), battery discharge A, status bits, ...
     */
    private fun parseQpigs(raw: String): TelemetrySnapshot {
        val body = raw.trim().removePrefix("(").trim()
        val fields = body.split(Regex("\\s+"))

        fun f(index: Int): Double = fields.getOrNull(index)?.toDoubleOrNull() ?: 0.0

        val loadWatts = f(5).toInt()
        val batteryVoltage = f(8)
        val batteryChargeA = f(9)
        val batterySoc = f(10).toInt()
        val tempC = f(11)
        val pvCurrent = f(12)
        val pvVoltage = f(13)
        val batteryDischargeA = f(15)

        val pvPower = (pvCurrent * pvVoltage).toInt()
        val batteryNetA = batteryChargeA - batteryDischargeA
        val batteryPower = (batteryNetA * batteryVoltage).toInt()

        return TelemetrySnapshot(
            batterySocPercent = batterySoc,
            batteryPowerWatts = batteryPower,
            pvPowerWatts = pvPower,
            gridPowerWatts = 0, // off-grid/line-interactive units report this via QPIGS2 on some models
            loadPowerWatts = loadWatts,
            inverterTempCelsius = tempC
        )
    }

    private fun sendCommand(command: String, timeoutMs: Int = 2500): String {
        val framed = framePi30Command(command)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val out = DataOutputStream(socket.getOutputStream())
            out.write(framed)
            out.flush()

            val input = DataInputStream(socket.getInputStream())
            val buffer = ByteArray(256)
            val read = input.read(buffer)
            require(read > 0) { "Empty response from Inverex/Voltronic unit at $ip:$port" }
            return String(buffer, 0, read, Charsets.US_ASCII)
        }
    }

    /** Appends the PI30 CRC16-CCITT and trailing <CR> (0x0D) that every command needs. */
    private fun framePi30Command(command: String): ByteArray {
        val cmdBytes = command.toByteArray(Charsets.US_ASCII)
        val crc = pi30Crc16(cmdBytes)
        return cmdBytes + byteArrayOf(((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte(), 0x0D)
    }

    /** PI30's CRC uses CRC16/CCITT-FALSE (poly 0x1021, init 0x0000). */
    private fun pi30Crc16(data: ByteArray): Int {
        var crc = 0x0000
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
