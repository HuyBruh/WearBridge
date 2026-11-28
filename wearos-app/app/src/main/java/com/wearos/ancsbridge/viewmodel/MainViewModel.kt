package com.wearos.ancsbridge.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ancs.NotificationPreferences
import com.wearos.ancsbridge.ble.BleConnectionManager
import com.wearos.ancsbridge.ble.BleScanner
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = BleScanner(application)
    val notificationPreferences = NotificationPreferences(application)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Observe the service's shared connection state directly
    val connectionState: StateFlow<ConnectionState> = AncsService.sharedConnectionState

    fun pairedPhones(): List<Pair<String, String>> {
        val manager = getApplication<Application>().getSystemService(BluetoothManager::class.java)
        return manager.adapter?.bondedDevices.orEmpty().filter {
            it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PHONE ||
                it.name.orEmpty().contains("iPhone", ignoreCase = true)
        }.map { (it.name ?: "Phone") to it.address }.sortedBy { it.first }
    }

    val supportedCommands = AncsService.supportedMediaCommands
    val phoneTools = AncsService.phoneTools
    val phoneSummary = AncsService.phoneSummary
    fun phoneControl(code: Int) {
        val context = getApplication<Application>()
        context.startService(Intent(context, AncsService::class.java).setAction("phone_control").putExtra("code", code))
    }
    // ---- Workouts & Apple Health ----
    private val healthOutbox = com.wearos.ancsbridge.health.HealthOutbox(application)
    val workoutState = com.wearos.ancsbridge.health.WorkoutService.state
    val healthPending = com.wearos.ancsbridge.health.HealthOutbox.pending
    val healthLastSync = com.wearos.ancsbridge.health.HealthOutbox.lastSync
    val healthSyncing = AncsService.healthSyncing
    val healthAvailable = AncsService.healthAvailable

    fun startWorkout(kind: com.wearos.ancsbridge.health.WorkoutKind) =
        com.wearos.ancsbridge.health.WorkoutService.start(getApplication(), kind)
    fun pauseWorkout() = com.wearos.ancsbridge.health.WorkoutService.pause(getApplication())
    fun resumeWorkout() = com.wearos.ancsbridge.health.WorkoutService.resume(getApplication())
    fun endWorkout() = com.wearos.ancsbridge.health.WorkoutService.end(getApplication())
    fun dismissWorkoutSummary() = com.wearos.ancsbridge.health.WorkoutService.dismissSummary()

    fun syncHealthNow() {
        val context = getApplication<Application>()
        context.startService(Intent(context, AncsService::class.java).setAction(AncsService.ACTION_SYNC_HEALTH))
    }

    fun passiveHealthEnabled() = com.wearos.ancsbridge.health.PassiveHealthService.isEnabled(getApplication())

    fun setPassiveHealth(enabled: Boolean, result: (Boolean) -> Unit) {
        viewModelScope.launch {
            val on = com.wearos.ancsbridge.health.PassiveHealthService.setEnabled(getApplication(), enabled)
            result(on)
        }
    }

    val sessionStats = AncsService.sessionStats
    val nowPlaying = AncsService.nowPlaying
    val mediaAvailable: StateFlow<Boolean> = AncsService.mediaAvailable

    fun sendMediaCommand(command: Int) {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_MEDIA_COMMAND
            putExtra(AncsService.EXTRA_MEDIA_COMMAND, command)
        }
        context.startService(intent)
    }

    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    private var scanJob: Job? = null

    val isBluetoothEnabled: Boolean get() = scanner.isBluetoothEnabled

    private val discoveredAppleDevices = mutableMapOf<String, ScanResult>()

    /**
     * Scan for Apple devices for 5 seconds, then connect to the strongest signal.
     * No device list is rendered — the scan runs silently with status updates.
     */
    fun scanAndConnect() {
        if (_isScanning.value) return
        _isScanning.value = true
        _scanStatus.value = "Looking for WearBridge app..."
        discoveredAppleDevices.clear()

        scanJob = viewModelScope.launch {
            // Phase 1: Scan for companion app (10 seconds)
            var found = false
            launch {
                delay(10_000)
                if (_isScanning.value && !found) {
                    _scanStatus.value = "WearBridge app not found.\nOpen it on your iPhone."
                    stopScan()
                }
            }

            try {
                scanner.scanForCompanion().collect { result ->
                    found = true
                    val name = result.device.name ?: result.device.address
                    Log.i("MainViewModel", "Found companion: $name rssi=${result.rssi}")
                    _scanStatus.value = "Found iPhone! Connecting..."
                    stopScan()
                    connectToDevice(result.device.address)
                    return@collect
                }
            } catch (e: Exception) {
                _scanStatus.value = "Scan error: ${e.message}"
                _isScanning.value = false
            }
        }
    }

    private fun connectToBestDevice() {
        if (discoveredAppleDevices.isEmpty()) {
            _scanStatus.value = "No Apple devices found"
            return
        }

        // Pick the device with the strongest signal
        val best = discoveredAppleDevices.values
            .sortedByDescending { it.rssi }
            .first()

        val name = best.device.name ?: best.device.address
        Log.i("MainViewModel", "Connecting to best Apple device: $name rssi=${best.rssi}")
        _scanStatus.value = "Connecting to $name..."
        connectToDevice(best.device.address)
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    fun connectToDevice(address: String) {
        stopScan()

        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_CONNECT
            putExtra(AncsService.EXTRA_DEVICE_ADDRESS, address)
        }
        context.startForegroundService(intent)
    }

    /**
     * Connect directly to a device by its Bluetooth MAC address.
     * Used when we know the iPhone's classic BT address from Settings.
     */
    fun connectByAddress(address: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_CONNECT
            putExtra(AncsService.EXTRA_DEVICE_ADDRESS, address)
        }
        context.startForegroundService(intent)
    }

    fun startService() {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    fun stopService() {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun disconnect() {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun hasBondedIPhone(): Boolean {
        val context = getApplication<Application>()
        if (BleConnectionManager.savedIPhone(context) != null) return true
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return false
        return adapter.bondedDevices.any { device ->
            val name = device.name ?: ""
            name.contains("iPhone", ignoreCase = true) ||
                name.contains("AncsBrid", ignoreCase = true) ||
                name.contains("AncsBridge", ignoreCase = true) ||
                name.contains("WearBridge", ignoreCase = true) ||
                name.contains("WearBrid", ignoreCase = true)
        }
    }

    /**
     * Clear all WearBridge notifications from the watch.
     * Frees up slots so new notifications can come through.
     */
    fun clearAllNotifications() {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_CLEAR_ALL
        }
        context.startService(intent)
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
