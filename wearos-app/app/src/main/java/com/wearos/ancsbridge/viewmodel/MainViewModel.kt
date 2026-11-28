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
import kotlinx.coroutines.flow.first

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

    fun startWorkout(
        kind: com.wearos.ancsbridge.health.WorkoutKind,
        goal: com.wearos.ancsbridge.health.WorkoutGoal = com.wearos.ancsbridge.health.WorkoutGoal.OPEN
    ) = com.wearos.ancsbridge.health.WorkoutService.start(getApplication(), kind, goal)
    // ---- Messages over Bluetooth MAP/PBAP ----
    val messagingStatus = com.wearos.ancsbridge.messages.PhoneMessaging.status
    val conversations = com.wearos.ancsbridge.messages.PhoneMessaging.conversations
    val contacts = com.wearos.ancsbridge.messages.PhoneMessaging.contacts
    fun messagingReady(): Boolean {
        com.wearos.ancsbridge.messages.PhoneMessaging.load(getApplication())
        return com.wearos.ancsbridge.messages.PhoneMessaging.isSetUp(getApplication())
    }
    fun refreshMessages() = viewModelScope.launch {
        com.wearos.ancsbridge.messages.PhoneMessaging.refresh(getApplication())
    }
    fun syncContacts() = viewModelScope.launch {
        com.wearos.ancsbridge.messages.PhoneMessaging.syncContacts(getApplication())
    }
    fun sendMessage(address: String, text: String, done: (Boolean) -> Unit) = viewModelScope.launch {
        done(com.wearos.ancsbridge.messages.PhoneMessaging.send(getApplication(), address, text))
    }
    fun forgetMessaging() = com.wearos.ancsbridge.messages.PhoneMessaging.forget(getApplication())
    val classicPairing by lazy { com.wearos.ancsbridge.messages.ClassicPairing(getApplication()) }

    // ---- Today: weather, calendar, reminders from the iPhone ----
    fun refreshPhoneInfo() {
        val context = getApplication<Application>()
        context.startService(Intent(context, AncsService::class.java).setAction(AncsService.ACTION_REFRESH_INFO))
    }
    fun completeReminder(id: String) {
        com.wearos.ancsbridge.model.PhoneInfo.removeReminder(id)
        val context = getApplication<Application>()
        context.startService(Intent(context, AncsService::class.java)
            .setAction(AncsService.ACTION_COMPLETE_REMINDER).putExtra("id", id))
    }

    fun markSegment() = com.wearos.ancsbridge.health.WorkoutService.segment(getApplication())
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
        _scanStatus.value = "Looking for your iPhone…"
        discoveredAppleDevices.clear()

        scanJob = viewModelScope.launch {
            // Meanwhile, note iPhones nearby (Apple "Nearby Info" adverts) as a fallback for
            // when the WearBridge app isn't open: ANCS works without it.
            val nearby = java.util.concurrent.ConcurrentHashMap<String, android.bluetooth.le.ScanResult>()
            val appleScan = launch {
                runCatching {
                    scanner.scan().collect { result ->
                        val apple = result.scanRecord?.getManufacturerSpecificData(0x004C)
                        if (result.isConnectable && apple != null && apple.isNotEmpty() && apple[0] == 0x10.toByte()) {
                            nearby[result.device.address] = result
                        }
                    }
                }
            }
            val result = try {
                // The iPhone app advertises whenever it is open.
                kotlinx.coroutines.withTimeoutOrNull(12_000) { scanner.scanForCompanion().first() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                appleScan.cancel()
            }
            _isScanning.value = false
            if (result != null) {
                Log.i("MainViewModel", "Found companion: ${result.device.address} rssi=${result.rssi}")
                _scanStatus.value = "Found iPhone. Connecting…"
                connectToDevice(result.device.address)
                return@launch
            }
            val closest = nearby.values.filter { it.rssi >= -62 }.maxByOrNull { it.rssi }
            if (closest != null) {
                Log.i("MainViewModel", "Trying nearby Apple device ${closest.device.address} rssi=${closest.rssi}")
                _scanStatus.value = "Connecting to the iPhone next to you… Accept the pairing request on the iPhone."
                connectToDevice(closest.device.address)
                return@launch
            }
            _scanStatus.value = "iPhone not found. Open the WearBridge app on your iPhone (or hold the watch right next to it), then Search again."
        }
    }

    /**
     * Removes this watch's pairings with the iPhone (notifications and Messages) so it can
     * pair again from scratch. Returns false if Android blocks removing bonds; the user then
     * removes the iPhone in the watch's Bluetooth settings.
     */
    fun forgetIPhone(): Boolean {
        val context = getApplication<Application>()
        disconnect()
        val devices = listOfNotNull(
            BleConnectionManager.savedIPhone(context),
            com.wearos.ancsbridge.messages.PhoneMessaging.device(context),
        ) + pairedPhones().mapNotNull { (_, address) ->
            context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.firstOrNull { it.address == address }
        }
        var removedAll = true
        devices.distinctBy { it.address }.forEach { if (!BleConnectionManager.removeBond(it)) removedAll = false }
        BleConnectionManager.clearSavedIPhone(context)
        com.wearos.ancsbridge.messages.PhoneMessaging.forget(context)
        return removedAll
    }

    fun openBluetoothSettings() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    /** Direct (fast) reconnect to the bonded iPhone. */
    fun reconnectNow() {
        val context = getApplication<Application>()
        runCatching {
            context.startForegroundService(Intent(context, AncsService::class.java).setAction(AncsService.ACTION_RECONNECT))
        }
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
        classicPairing.stop()
    }
}
