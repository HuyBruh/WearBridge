package com.wearos.ancsbridge.messages

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds the iPhone over Bluetooth Classic and bonds with it. Messages/contacts (MAP/PBAP)
 * run over Classic, which needs its own bond next to the BLE one used for notifications.
 * The iPhone is only discoverable over Classic while Settings › Bluetooth is open.
 */
@SuppressLint("MissingPermission")
class ClassicPairing(private val context: Context) {

    data class Found(val device: BluetoothDevice, val name: String)

    sealed interface State {
        data object Idle : State
        data object Searching : State
        data class Pairing(val name: String) : State
        data class Paired(val name: String) : State
        data class Failed(val message: String) : State
    }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var pairingAddress: String? = null
    private var registered = false
    private var rebonding = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    val major = device.bluetoothClass?.majorDeviceClass
                    val name = device.name ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: return
                    if (major == BluetoothClass.Device.Major.PHONE || name.contains("iPhone", true)) {
                        if (_found.value.none { it.device.address == device.address }) {
                            _found.value = _found.value + Found(device, name)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_state.value == State.Searching) {
                        _state.value = if (_found.value.isEmpty())
                            State.Failed("No iPhone found. Keep Settings › Bluetooth open on the iPhone.")
                        else State.Idle
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    if (device.address != pairingAddress) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> {
                            PhoneMessaging.choose(context, device)
                            _state.value = State.Paired(device.name ?: "iPhone")
                        }
                        BluetoothDevice.BOND_NONE -> {
                            if (rebonding) {
                                // Old bond removed: pair again so iOS sees the new service records.
                                rebonding = false
                                _state.value = if (device.createBond()) State.Pairing(device.name ?: "iPhone")
                                else State.Failed("Couldn't start pairing")
                            } else {
                                _state.value = State.Failed("Pairing was cancelled")
                            }
                        }
                    }
                }
            }
        }
    }

    fun start() {
        // iOS reads the watch's service records while pairing: publish them first.
        MapServerRecords.start(context)
        if (!registered) {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            })
            registered = true
        }
        _found.value = PhoneMessaging.bondedPhones(context).map { Found(it, it.name ?: "iPhone") }
        val adapter = adapter ?: return run { _state.value = State.Failed("Bluetooth unavailable") }
        adapter.cancelDiscovery()
        _state.value = if (adapter.startDiscovery()) State.Searching
        else State.Failed("Couldn't search. Turn Bluetooth on and allow Nearby devices.")
    }

    fun pair(found: Found) {
        adapter?.cancelDiscovery()
        val device = found.device
        pairingAddress = device.address
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            // Re-pair: the iPhone only reads our MAP/PBAP records when it pairs, and it may
            // have forgotten this watch. removeBond is hidden API; if it is blocked, keep the
            // existing bond and let the user remove it in watch Bluetooth settings.
            val removed = runCatching { device.javaClass.getMethod("removeBond").invoke(device) as Boolean }.getOrDefault(false)
            if (removed) {
                rebonding = true
                _state.value = State.Pairing(found.name)
            } else {
                PhoneMessaging.choose(context, device)
                _state.value = State.Paired(found.name)
            }
            return
        }
        _state.value = if (device.createBond()) State.Pairing(found.name)
        else State.Failed("Couldn't start pairing")
    }

    fun stop() {
        adapter?.cancelDiscovery()
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }
}
