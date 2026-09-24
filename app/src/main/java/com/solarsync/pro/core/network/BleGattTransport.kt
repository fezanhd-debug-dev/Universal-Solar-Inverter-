package com.solarsync.pro.core.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Shared BLE transport for controllers that proxy Modbus RTU frames over a
 * simple write-characteristic / notify-characteristic pair — the pattern
 * used (with different UUIDs) by Renogy BT-1/BT-2, EPEVER's BLE dongle, and
 * SRNE's BT-2 module. One GATT connection is opened per read/write call and
 * closed afterwards, since these modules only support a single concurrent
 * connection and drop idle links after a short timeout anyway.
 *
 * NOTE: service/characteristic UUIDs are commonly reported values for each
 * brand's official BLE dongle. Some third-party or older dongle firmware
 * revisions use different UUIDs — if a device is found by name/scan but
 * writes/notifications never arrive, check the exact UUIDs with a generic
 * BLE scanner app (e.g. nRF Connect) against your specific module.
 */
class BleGattTransport(
    private val context: Context,
    private val macAddress: String,
    private val writeCharacteristicUuid: UUID,
    private val notifyCharacteristicUuid: UUID
) {
    companion object {
        // Standard BLE Client Characteristic Configuration descriptor, used to enable notifications.
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @SuppressLint("MissingPermission")
    suspend fun writeAndAwaitResponse(
        payload: ByteArray,
        responseTimeoutMs: Long = 4000
    ): ByteArray = withTimeout(responseTimeoutMs + 4000) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter
            ?: throw IllegalStateException("Bluetooth is not available on this device")
        val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)

        val notifyBuffer = mutableListOf<Byte>()
        val responseDeferred = CompletableDeferred<Unit>()
        var gattRef: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!responseDeferred.isCompleted) responseDeferred.complete(Unit)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val notifyChar = gatt.services
                    .flatMap { it.characteristics }
                    .firstOrNull { it.uuid == notifyCharacteristicUuid }
                val writeChar = gatt.services
                    .flatMap { it.characteristics }
                    .firstOrNull { it.uuid == writeCharacteristicUuid }

                if (notifyChar == null || writeChar == null) {
                    responseDeferred.completeExceptionally(
                        IllegalStateException("Expected BLE characteristics not found on $macAddress")
                    )
                    return
                }

                gatt.setCharacteristicNotification(notifyChar, true)
                val descriptor = notifyChar.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                } else {
                    writeChar.value = payload
                    gatt.writeCharacteristic(writeChar)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val writeChar = gatt.services
                    .flatMap { it.characteristics }
                    .firstOrNull { it.uuid == writeCharacteristicUuid }
                if (writeChar != null) {
                    writeChar.value = payload
                    gatt.writeCharacteristic(writeChar)
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == notifyCharacteristicUuid) {
                    notifyBuffer.addAll(characteristic.value.toList())
                    // Most of these dongles send the full Modbus RTU reply (with CRC) in one
                    // notification per PDU; a short quiet period after the first notification
                    // is used below as the "response complete" signal.
                    if (!responseDeferred.isCompleted) responseDeferred.complete(Unit)
                }
            }
        }

        gattRef = device.connectGatt(context, false, callback)

        try {
            withTimeoutOrNull(responseTimeoutMs) { responseDeferred.await() }
            // Give any trailing notification fragments a brief moment to arrive.
            delay(150)
        } finally {
            gattRef.disconnect()
            gattRef.close()
        }

        if (notifyBuffer.isEmpty()) {
            throw IllegalStateException("No BLE response from $macAddress (module may be asleep or out of range)")
        }
        notifyBuffer.toByteArray()
    }
}
