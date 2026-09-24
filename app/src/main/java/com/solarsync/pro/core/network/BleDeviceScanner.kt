package com.solarsync.pro.core.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One BLE device found during a scan, before it's matched to a specific brand adapter. */
data class BleScanHit(val macAddress: String, val name: String)

/**
 * Scans for nearby BLE devices and returns any whose advertised name
 * matches one of the given prefixes (e.g. Renogy's "BT-TH", SRNE's "BT-2").
 * Kept separate from BleGattTransport since scanning doesn't need a target
 * MAC address or characteristic UUIDs — only a connect (once a device is
 * chosen) does.
 */
class BleDeviceScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun scanForNames(
        namePrefixes: List<String>,
        scanDurationMs: Long = 6000
    ): List<BleScanHit> = suspendCancellableCoroutine { continuation ->
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val found = linkedMapOf<String, String>() // mac -> name
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (namePrefixes.any { prefix -> name.startsWith(prefix, ignoreCase = true) }) {
                    found[result.device.address] = name
                }
            }
        }

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(emptyList<ScanFilter>(), settings, callback)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            scanner.stopScan(callback)
            if (continuation.isActive) {
                continuation.resume(found.map { (mac, name) -> BleScanHit(mac, name) })
            }
        }, scanDurationMs)

        continuation.invokeOnCancellation { scanner.stopScan(callback) }
    }
}
