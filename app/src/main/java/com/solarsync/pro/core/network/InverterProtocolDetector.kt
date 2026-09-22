package com.solarsync.pro.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Sweeps the well-known ports each supported inverter brand listens on and
 * reports back which protocol (if any) answered, so the app can pick the
 * right adapter automatically instead of asking the user.
 */
class InverterProtocolDetector {

    enum class DetectedProtocol {
        SOLARMAN_V5,     // Deye / Knox (Krypton, Argon) dongles - port 8899
        PI30_ASCII,      // Inverex Nitrox/Aerox, Voltronic Axpert - port 5000 / 23
        MODBUS_TCP_SOLIS,// Solis (Ginlong) local Modbus - port 502 / 10000
        SUNSPEC_MODBUS,  // Fronius, SolaX, Growatt SunSpec - port 502
        UNKNOWN
    }

    data class ScanResult(
        val ipAddress: String,
        val openPort: Int,
        val protocol: DetectedProtocol
    )

    // Port -> candidate protocol. Order matters: more specific/faster to
    // confirm ports go first so common brands resolve quickly.
    private val portProtocolMap = linkedMapOf(
        8899 to DetectedProtocol.SOLARMAN_V5,
        502 to DetectedProtocol.SUNSPEC_MODBUS,   // shared with Solis, disambiguated below
        10000 to DetectedProtocol.MODBUS_TCP_SOLIS,
        5000 to DetectedProtocol.PI30_ASCII,
        23 to DetectedProtocol.PI30_ASCII
    )

    private val socketTimeoutMs = 600

    /** Quick TCP connect check — cheap and doesn't require the device's real protocol. */
    private suspend fun isPortOpen(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), socketTimeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Probes a single IP across all known inverter ports concurrently.
     * Returns the first protocol that answers, or UNKNOWN if none respond.
     */
    suspend fun detectDevice(ip: String): ScanResult? = withContext(Dispatchers.IO) {
        val jobs = portProtocolMap.map { (port, protocol) ->
            async {
                if (isPortOpen(ip, port)) ScanResult(ip, port, resolveAmbiguity(ip, port, protocol))
                else null
            }
        }
        jobs.awaitAll().filterNotNull().firstOrNull()
    }

    /**
     * Port 502 is shared by Solis local Modbus and SunSpec (Fronius/SolaX/Growatt).
     * We disambiguate by reading the SunSpec "SunS" marker at register 40000;
     * if it's absent we fall back to treating it as Solis Modbus.
     */
    private suspend fun resolveAmbiguity(
        ip: String,
        port: Int,
        fallback: DetectedProtocol
    ): DetectedProtocol {
        if (port != 502) return fallback
        return try {
            val marker = ModbusPacketCodec.readHoldingRegistersTcp(
                ip = ip,
                port = port,
                unitId = 1,
                startAddress = 40000,
                quantity = 2,
                timeoutMs = socketTimeoutMs
            )
            val text = ModbusPacketCodec.registersToAscii(marker)
            if (text.startsWith("SunS")) DetectedProtocol.SUNSPEC_MODBUS
            else DetectedProtocol.MODBUS_TCP_SOLIS
        } catch (e: SocketTimeoutException) {
            fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /**
     * Sweeps a full /24 subnet (e.g. "192.168.1") for any responding inverter.
     * Runs host probes concurrently in bounded batches to avoid flooding the
     * phone's WiFi radio with 254 simultaneous sockets.
     */
    suspend fun scanSubnet(
        subnetPrefix: String,
        onDeviceFound: suspend (ScanResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val batchSize = 32
        (1..254).chunked(batchSize).forEach { batch ->
            val jobs = batch.map { host ->
                async {
                    val ip = "$subnetPrefix.$host"
                    detectDevice(ip)
                }
            }
            jobs.awaitAll().filterNotNull().forEach { onDeviceFound(it) }
        }
    }
}
