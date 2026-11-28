package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.util.Log
import java.util.UUID

/**
 * Thin BluetoothGattCallback: filters out callbacks from stale connections and forwards
 * everything to [BleConnectionManager]. minSdk is 33, so only the value-carrying
 * overloads (API 33+) are used; the deprecated ones are never called by the stack.
 */
@SuppressLint("MissingPermission")
class GattCallback(
    private val isCurrentConnection: (BluetoothGatt) -> Boolean,
    private val onConnected: (BluetoothGatt) -> Unit,
    private val onDisconnected: (BluetoothGatt, Int) -> Unit,
    private val onServicesDiscoveredCallback: (BluetoothGatt, Int) -> Unit,
    private val onServiceChangedCallback: (BluetoothGatt) -> Unit,
    private val onMtuChanged: (Int, Int) -> Unit,
    /** Completion of a queued GATT operation: characteristic/descriptor UUID, status, read value. */
    private val onOperationComplete: (UUID, Int, ByteArray?) -> Unit,
    private val onCharacteristicChanged: (UUID, ByteArray) -> Unit,
) : BluetoothGattCallback() {

    companion object {
        private const val TAG = "GattCallback"
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (!isCurrentConnection(gatt)) return
        Log.d(TAG, "Connection state changed: status=$status newState=$newState")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> onConnected(gatt)
            BluetoothProfile.STATE_DISCONNECTED -> onDisconnected(gatt, status)
        }
    }

    override fun onServiceChanged(gatt: BluetoothGatt) {
        if (isCurrentConnection(gatt)) onServiceChangedCallback(gatt)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (!isCurrentConnection(gatt)) return
        Log.d(TAG, "Services discovered: status=$status count=${gatt.services.size}")
        onServicesDiscoveredCallback(gatt, status)
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (!isCurrentConnection(gatt)) return
        Log.d(TAG, "MTU changed: mtu=$mtu status=$status")
        onMtuChanged(mtu, status)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (!isCurrentConnection(gatt)) return
        onOperationComplete(characteristic.uuid, status, value)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        if (!isCurrentConnection(gatt)) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "Write failed: ${characteristic.uuid} status=$status")
        }
        onOperationComplete(characteristic.uuid, status, null)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        if (!isCurrentConnection(gatt)) return
        Log.d(TAG, "Descriptor written: ${descriptor.characteristic.uuid} status=$status")
        // Keyed by the characteristic so the queue can match "subscribe to X".
        onOperationComplete(descriptor.characteristic.uuid, status, null)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (!isCurrentConnection(gatt)) return
        onCharacteristicChanged(characteristic.uuid, value)
    }
}
