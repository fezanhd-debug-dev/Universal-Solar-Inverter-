package com.solarsync.pro.protocols

import com.solarsync.pro.core.network.ModbusPacketCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * Adapter for Solis (Ginlong) hybrid inverters. Prefers the local Modbus TCP
 * interface (port 502, sometimes 10000 on older loggers) for low-latency
 * polling, and falls back to the SolisCloud REST API when the inverter
 * isn't reachable on the LAN (e.g. app used away from home).
 */
class SolisCloudProtocolAdapter(
    private val ip: String,
    private val port: Int = 502,
    private val unitId: Int = 1,
    // Only required for the cloud fallback path:
    private val cloudKeyId: String? = null,
    private val cloudKeySecret: String? = null,
    private val inverterSerial: String? = null
) : InverterAdapter {

    private object Registers {
        const val START = 33000 // Solis input-register block start (varies by firmware gen)
        const val PV_POWER = 33057
        const val BATTERY_SOC = 33139
        const val BATTERY_POWER = 33149
        const val GRID_POWER = 33130
        const val LOAD_POWER = 33147
    }

    override suspend fun readTelemetry(): TelemetrySnapshot = withContext(Dispatchers.IO) {
        try {
            readLocalTelemetry()
        } catch (e: Exception) {
            if (cloudKeyId != null && cloudKeySecret != null && inverterSerial != null) {
                readCloudTelemetry()
            } else {
                throw e
            }
        }
    }

    override suspend fun setWorkMode(mode: InverterWorkMode): Boolean = withContext(Dispatchers.IO) {
        // Solis energy-storage control register 43110: 0=self-use,1=time-of-use,2=backup
        val value = when (mode) {
            InverterWorkMode.SELF_USE -> 0
            InverterWorkMode.GRID_PRIORITY -> 1
            InverterWorkMode.BATTERY_PRIORITY -> 2
        }
        try {
            val pdu = byteArrayOf(
                0x06,
                (43110 shr 8).toByte(), (43110 and 0xFF).toByte(),
                (value shr 8).toByte(), (value and 0xFF).toByte()
            )
            val frame = ModbusPacketCodec.buildModbusTcpFrame(
                transactionId = (System.currentTimeMillis() and 0xFFFF).toInt(),
                unitId = unitId,
                pdu = pdu
            )
            // Local write path reuses the same socket helper pattern as the read below.
            ModbusPacketCodec.readHoldingRegistersTcp(ip, port, unitId, 43110, 1) // confirms link is alive
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun readLocalTelemetry(): TelemetrySnapshot {
        val regs = ModbusPacketCodec.readHoldingRegistersTcp(
            ip = ip,
            port = port,
            unitId = unitId,
            startAddress = Registers.START,
            quantity = 160
        )

        fun at(address: Int): Int = regs.getOrElse(address - Registers.START) { 0 }
        fun signed(v: Int): Int = if (v > 32767) v - 65536 else v

        return TelemetrySnapshot(
            batterySocPercent = at(Registers.BATTERY_SOC),
            batteryPowerWatts = signed(at(Registers.BATTERY_POWER)),
            pvPowerWatts = at(Registers.PV_POWER),
            gridPowerWatts = signed(at(Registers.GRID_POWER)),
            loadPowerWatts = at(Registers.LOAD_POWER),
            inverterTempCelsius = 0.0 // not in this register block; add 33093 if needed
        )
    }

    /**
     * SolisCloud's open API requires an HMAC-SHA1 signed request
     * (Content-MD5 + Date + resource path signed with the key secret).
     * This mirrors that signing scheme for the station "inverterDetail" endpoint.
     */
    private fun readCloudTelemetry(): TelemetrySnapshot {
        val resourcePath = "/v1/api/inverterDetail"
        val body = JSONObject().apply { put("sn", inverterSerial) }.toString()

        val contentMd5 = Base64.encodeToString(
            MessageDigest.getInstance("MD5").digest(body.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        val dateHeader = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            .format(Date())

        val stringToSign = "POST\n$contentMd5\napplication/json\n$dateHeader\n$resourcePath"
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(cloudKeySecret!!.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        }
        val signature = Base64.encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val authHeader = "API $cloudKeyId:$signature"

        val request = Request.Builder()
            .url("https://www.soliscloud.com:13333$resourcePath")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
            .addHeader("Content-MD5", contentMd5)
            .addHeader("Content-Type", "application/json")
            .addHeader("Date", dateHeader)
            .addHeader("Authorization", authHeader)
            .build()

        OkHttpClient().newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            val data = json.optJSONObject("data") ?: JSONObject()
            return TelemetrySnapshot(
                batterySocPercent = data.optInt("batteryCapacitySoc", 0),
                batteryPowerWatts = data.optInt("batteryPower", 0),
                pvPowerWatts = data.optInt("pac", 0),
                gridPowerWatts = data.optInt("psum", 0),
                loadPowerWatts = data.optInt("familyLoadPower", 0),
                inverterTempCelsius = data.optDouble("inverterTemperature", 0.0)
            )
        }
    }
}

private fun String.toMediaTypeOrNull() = okhttp3.MediaType.parse(this)
