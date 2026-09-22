package com.solarsync.pro.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Low-level codec shared by every adapter:
 *  - Modbus RTU CRC16 (used inside Solarman V5 frames and raw RTU-over-TCP)
 *  - Modbus TCP (MBAP header) read/write helpers, used by Solis + SunSpec
 *  - Solarman/IGEN V5 frame wrapper, used by Deye/Knox dongles
 */
object ModbusPacketCodec {

    // ---------------------------------------------------------------
    // Modbus CRC16 (poly 0xA001, standard Modbus RTU table-less version)
    // ---------------------------------------------------------------
    fun crc16Modbus(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x0001 != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /** Appends the CRC16 (low byte first) to a Modbus RTU frame. */
    fun appendCrc(frame: ByteArray): ByteArray {
        val crc = crc16Modbus(frame)
        val lo = (crc and 0xFF).toByte()
        val hi = ((crc shr 8) and 0xFF).toByte()
        return frame + byteArrayOf(lo, hi)
    }

    // ---------------------------------------------------------------
    // Modbus TCP (MBAP header): [transId(2)][protoId(2)=0][len(2)][unitId(1)][PDU...]
    // ---------------------------------------------------------------
    private fun buildReadHoldingRegistersPdu(startAddress: Int, quantity: Int): ByteArray {
        return byteArrayOf(
            0x03, // function code: Read Holding Registers
            (startAddress shr 8).toByte(), (startAddress and 0xFF).toByte(),
            (quantity shr 8).toByte(), (quantity and 0xFF).toByte()
        )
    }

    fun buildModbusTcpFrame(transactionId: Int, unitId: Int, pdu: ByteArray): ByteArray {
        val length = pdu.size + 1 // + unit id byte
        return byteArrayOf(
            (transactionId shr 8).toByte(), (transactionId and 0xFF).toByte(),
            0x00, 0x00, // protocol id, always 0 for Modbus
            (length shr 8).toByte(), (length and 0xFF).toByte(),
            unitId.toByte()
        ) + pdu
    }

    /**
     * Opens a short-lived TCP socket, sends a Read Holding Registers request,
     * and returns the raw 16-bit register values. Used by Solis, SunSpec,
     * and the brand-detection probe.
     */
    suspend fun readHoldingRegistersTcp(
        ip: String,
        port: Int,
        unitId: Int,
        startAddress: Int,
        quantity: Int,
        timeoutMs: Int = 1500
    ): IntArray = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val transactionId = (System.currentTimeMillis() and 0xFFFF).toInt()
            val pdu = buildReadHoldingRegistersPdu(startAddress, quantity)
            val frame = buildModbusTcpFrame(transactionId, unitId, pdu)

            val out = DataOutputStream(socket.getOutputStream())
            out.write(frame)
            out.flush()

            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(7)
            input.readFully(header)
            val respLength = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)

            val pduResp = ByteArray(respLength - 1)
            input.readFully(pduResp)

            // pduResp[0] = function code, pduResp[1] = byte count, rest = register data
            val byteCount = pduResp[1].toInt() and 0xFF
            val registerCount = byteCount / 2
            IntArray(registerCount) { i ->
                val hi = pduResp[2 + i * 2].toInt() and 0xFF
                val lo = pduResp[3 + i * 2].toInt() and 0xFF
                (hi shl 8) or lo
            }
        }
    }

    /** Interprets a register block as ASCII text (2 chars per register, hi byte first). */
    fun registersToAscii(registers: IntArray): String {
        val sb = StringBuilder()
        for (reg in registers) {
            sb.append(((reg shr 8) and 0xFF).toChar())
            sb.append((reg and 0xFF).toChar())
        }
        return sb.toString().trim('\u0000')
    }

    // ---------------------------------------------------------------
    // Solarman / IGEN "V5" frame wrapper — wraps a Modbus RTU PDU for
    // transport over the Deye/Knox WiFi dongle's TCP port 8899.
    // Frame: 0xA5 [len_lo][len_hi] [ctrl=0x1045] [serial(2)] [0x02] [loggerSN(4,LE)]
    //        [frameType=0x02] [sensorType(2)=0x0000] [totalWorking(4)][powerOnTime(4)]
    //        [offsetTime(4)] [payload: Modbus RTU frame w/ CRC] [0x00][checksum][0x15]
    // A pragmatic subset is implemented here — enough to send a Modbus RTU
    // read/write PDU and unwrap the Modbus response embedded in the reply.
    // ---------------------------------------------------------------
    private const val SOLARMAN_START: Byte = 0xA5.toByte()
    private const val SOLARMAN_END: Byte = 0x15

    fun buildSolarmanV5Frame(loggerSerial: Long, modbusRtuPayload: ByteArray): ByteArray {
        val controlCode = byteArrayOf(0x45, 0x10) // request, business data
        val frameSerial = byteArrayOf(0x00, 0x00)
        val serialBytes = byteArrayOf(
            (loggerSerial and 0xFF).toByte(),
            ((loggerSerial shr 8) and 0xFF).toByte(),
            ((loggerSerial shr 16) and 0xFF).toByte(),
            ((loggerSerial shr 24) and 0xFF).toByte()
        )
        val frameType = byteArrayOf(0x02)
        val reserved = ByteArray(13) // sensorType + counters, zeroed for a simple read

        val body = frameType + reserved + modbusRtuPayload
        val payload = byteArrayOf(0x02) + serialBytes + body

        val length = payload.size
        val header = byteArrayOf(
            SOLARMAN_START,
            (length and 0xFF).toByte(), ((length shr 8) and 0xFF).toByte()
        ) + controlCode + frameSerial

        val withoutChecksum = header + payload
        val checksum = withoutChecksum.drop(1).fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFF

        return withoutChecksum + byteArrayOf(checksum.toByte(), SOLARMAN_END)
    }

    /** Strips the Solarman V5 envelope and returns the raw Modbus RTU response bytes. */
    fun unwrapSolarmanV5Frame(raw: ByteArray): ByteArray {
        require(raw.isNotEmpty() && raw[0] == SOLARMAN_START) { "Not a Solarman V5 frame" }
        // header(1+2+2+2) + frameType/control(1) + serial(4) + reserved(13) = 25 bytes prefix
        val prefixLen = 1 + 2 + 2 + 2 + 1 + 4 + 13
        val end = raw.size - 2 // checksum + end byte
        return raw.copyOfRange(prefixLen, end)
    }
}
