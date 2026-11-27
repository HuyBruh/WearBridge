package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages the BLE GATT connection to an iPhone and the ANCS subscription lifecycle.
 *
 * Flow: connect → bond → discover services → subscribe DS → subscribe NS → active
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) {

    companion object {
        private val MEDIA_SERVICE_UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
        private val MEDIA_REMOTE_COMMAND_UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
        private val MEDIA_ENTITY_UPDATE_UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
        private const val TAG = "BleConnectionManager"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 3_000L
        private const val TARGET_MTU = 512
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2
        private const val PREFS_NAME = "wearbridge"
        private const val KEY_PAIRED_IPHONE = "paired_iphone_address"

        /**
         * The bonded device that last gave us an ANCS session. Name matching alone misses
         * iPhones that were renamed, or that bonded without a name (paired from iPhone
         * Settings -> Bluetooth), so getBondedIPhone() returned null after a reboot.
         */
        fun savedIPhone(context: Context): BluetoothDevice? {
            val address = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PAIRED_IPHONE, null) ?: return null
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            return adapter?.bondedDevices?.firstOrNull { it.address == address }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Android delivers these on the main handler. Preserve ordering across both
    // characteristics, including bursts and events received during subscription.
    var onNotificationEvent: (ByteArray) -> Unit = {}
    var onAttributeData: (ByteArray) -> Unit = {}
    var onAttributeRequestFailed: () -> Unit = {}
    private var lastControlCommand: Byte? = null
    var onSessionReset: () -> Unit = {}
    private val _callStateEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val callStateEvents: SharedFlow<ByteArray> = _callStateEvents.asSharedFlow()

    val supportedCommands = MutableStateFlow<Set<Int>?>(null)
    private val _mediaAvailable = MutableStateFlow(false)
    val mediaAvailable: StateFlow<Boolean> = _mediaAvailable.asStateFlow()

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val workoutUUID = UUID.fromString("A0E3D5B7-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    suspend fun sendWorkout(data: ByteArray) = companionWrite(
        gatt?.getService(AncsConstants.COMPANION_SERVICE_UUID)?.getCharacteristic(workoutUUID), data)

    private val toolsUUID = UUID.fromString("A0E3D5B6-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    private fun toolsCharacteristic() = gatt?.getService(AncsConstants.COMPANION_SERVICE_UUID)?.getCharacteristic(toolsUUID)
    val phoneTools = MutableStateFlow(false)
    val phoneSummary = MutableStateFlow("")
    fun refreshPhone(): Boolean {
        val characteristic = toolsCharacteristic() ?: return false
        phoneSummary.value = ""
        return gatt?.readCharacteristic(characteristic) == true
    }

    suspend fun phoneCommand(code: Int): Boolean {
        if (code !in 1..2) return false
        return companionWrite(toolsCharacteristic(), byteArrayOf(code.toByte()))
    }

    private val draftUUID = UUID.fromString("A0E3D5B5-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    private fun draftCharacteristic() = gatt?.getService(AncsConstants.COMPANION_SERVICE_UUID)?.getCharacteristic(draftUUID)
    val canDraft get() = draftCharacteristic() != null
    private var draftResult: CompletableDeferred<Boolean>? = null
    private var mtuBytes = 23

    suspend fun saveDraft(data: ByteArray) = companionWrite(draftCharacteristic(), data)

    private suspend fun companionWrite(target: BluetoothGattCharacteristic?, data: ByteArray): Boolean {
        val session = gatt ?: return false
        val characteristic = target ?: return false
        if (draftResult != null || data.size > minOf(512, mtuBytes - 3)) return false
        val result = CompletableDeferred<Boolean>()
        draftResult = result
        try {
            repeat(4) {
                if (session !== gatt) return false
                if (writeWithResponse(session, characteristic, data)) {
                    return withTimeoutOrNull(4_000) { result.await() } ?: false
                }
                delay(80)
            }
            return false
        } finally {
            if (draftResult === result) draftResult = null
        }
    }

    private var mediaCommandResult: CompletableDeferred<Boolean>? = null
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var autoReconnect = true
    /** The user chose Disconnect: Bluetooth coming back on must not reconnect. */
    private var userDisconnected = false

    // CCCD write queue — must serialize descriptor writes
    private val descriptorWriteQueue = LinkedList<Pair<BluetoothGattCharacteristic, BluetoothGattDescriptor>>()
    private var isWritingDescriptor = false

    private var serviceDiscoveryRetries = 0
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    // Bond events come for every device the watch pairs with (earbuds...): only the iPhone's matter.
    private fun isTarget(device: BluetoothDevice) = device.address == targetDevice?.address

    val bondStateReceiver = BondStateReceiver(
        onBonded = { device ->
            if (!isTarget(device)) return@BondStateReceiver
            Log.i(TAG, "Bond completed, re-discovering services")
            // Small delay after bonding before service discovery
            scope.launch {
                delay(1000)
                gatt?.discoverServices()
            }
        },
        onBondFailed = { device ->
            if (!isTarget(device)) return@BondStateReceiver
            Log.e(TAG, "Bond failed")
            _connectionState.value = ConnectionState.Error("Pairing failed")
            disconnect()
        }
    )

    private val gattCallback = GattCallback(
        isCurrentConnection = { it === gatt },
        onConnected = { gatt ->
            reconnectDelay = INITIAL_RECONNECT_DELAY_MS
            val displayName = if (isTruncatedBleName(gatt.device.name ?: "")) "iPhone" else (gatt.device.name ?: "iPhone")
            _connectionState.value = ConnectionState.Connecting(displayName)

            // Request higher MTU for less fragmentation
            if (!gatt.requestMtu(TARGET_MTU)) gatt.discoverServices()
        },
        onDisconnected = { gatt, status ->
            Log.i(TAG, "Disconnected, status=$status")
            _connectionState.value = ConnectionState.Disconnected()
            clearCharacteristics()
            gatt.close()
            this.gatt = null

            if (autoReconnect && targetDevice != null) {
                scheduleReconnect()
            }
        },
        onServicesDiscoveredCallback = { gatt, status ->
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _connectionState.value = ConnectionState.Error("Service discovery failed")
                // Drop the link so the normal reconnect starts over, instead of
                // leaving a live link with nothing subscribed.
                gatt.disconnect()
                return@GattCallback
            }

            val ancsService = gatt.getService(AncsConstants.ANCS_SERVICE_UUID)
            if (ancsService == null) {
                // ANCS not visible — likely not bonded yet
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "ANCS not found, initiating bonding")
                    val bondDisplayName = if (isTruncatedBleName(gatt.device.name ?: "")) "iPhone" else (gatt.device.name ?: "iPhone")
                    _connectionState.value = ConnectionState.Bonding(bondDisplayName)
                    gatt.device.createBond()
                } else {
                    // iOS may publish ANCS after the bond is ready. Keep retries
                    // bounded on this link and stop if the user disconnects meanwhile.
                    serviceDiscoveryRetries++
                    if (serviceDiscoveryRetries <= MAX_SERVICE_DISCOVERY_RETRIES) {
                        Log.w(TAG, "ANCS unavailable; retry $serviceDiscoveryRetries/$MAX_SERVICE_DISCOVERY_RETRIES")
                        scope.launch {
                            delay(2_000)
                            if (this@BleConnectionManager.gatt === gatt && !gatt.discoverServices()) {
                                stopAfterDiscoveryFailure("Could not discover iPhone services")
                            }
                        }
                    } else {
                        stopAfterDiscoveryFailure("iPhone notification service unavailable")
                    }
                }
                return@GattCallback
            }

            // ANCS service found — get characteristics
            Log.i(TAG, "ANCS service found!")
            serviceDiscoveryRetries = 0

            // If already subscribed (rediscovery for companion service), just read companion
            if (notificationSourceChar != null && dataSourceChar != null) {
                Log.i(TAG, "ANCS already subscribed, checking companion service")
                readCompanionStatus()
                return@GattCallback
            }

            notificationSourceChar = ancsService.getCharacteristic(AncsConstants.NOTIFICATION_SOURCE_UUID)
            controlPointChar = ancsService.getCharacteristic(AncsConstants.CONTROL_POINT_UUID)
            dataSourceChar = ancsService.getCharacteristic(AncsConstants.DATA_SOURCE_UUID)

            if (notificationSourceChar == null || dataSourceChar == null || controlPointChar == null) {
                Log.e(TAG, "Missing ANCS characteristics")
                _connectionState.value = ConnectionState.Error("ANCS characteristics missing")
                return@GattCallback
            }

            // Subscribe to Data Source FIRST (Apple recommendation),
            // then Notification Source
            subscribeToCharacteristic(dataSourceChar!!)
        },
        onNotificationSourceChanged = { data ->
            onNotificationEvent(data)
        },
        onDataSourceChanged = { data ->
            onAttributeData(data)
        },
        onDescriptorWritten = { descriptor, status ->
            isWritingDescriptor = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed: $status for ${descriptor.characteristic.uuid}")
                when (descriptor.characteristic.uuid) {
                    // Without these there is no ANCS session: start over.
                    AncsConstants.DATA_SOURCE_UUID, AncsConstants.NOTIFICATION_SOURCE_UUID -> gatt?.disconnect()
                    // Companion subscriptions are optional: carry on with the queue.
                    else -> processDescriptorWriteQueue()
                }
                return@GattCallback
            }

            val charUuid = descriptor.characteristic.uuid
            when (charUuid) {
                MEDIA_ENTITY_UPDATE_UUID -> {
                    val session = gatt
                    val characteristic = descriptor.characteristic
                    scope.launch {
                        repeat(4) { attempt ->
                            if (gatt !== session || session == null) return@launch
                            if (writeWithResponse(session, characteristic, NowPlaying.trackSubscription())) {
                                return@launch
                            }
                            if (attempt < 3) delay(100)
                        }
                        Log.w(TAG, "Could not request media track metadata")
                    }
                }
                AncsConstants.DATA_SOURCE_UUID -> {
                    Log.i(TAG, "Subscribed to Data Source, now subscribing to Notification Source")
                    notificationSourceChar?.let { subscribeToCharacteristic(it) }
                }
                AncsConstants.NOTIFICATION_SOURCE_UUID -> {
                    Log.i(TAG, "Subscribed to Notification Source — ANCS session active!")
                    gatt?.device?.address?.let { address ->
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_PAIRED_IPHONE, address).apply()
                    }
                    // The bonded-but-no-ANCS retry connects with autoReconnect off; a working
                    // session should reconnect on its own again.
                    if (targetDevice != null) autoReconnect = true
                    // Show initial name (may be truncated BLE name)
                    val deviceName = resolveDeviceName()
                    _connectionState.value = ConnectionState.Connected(deviceName)
                    phoneTools.value = toolsCharacteristic() != null
                    _mediaAvailable.value = mediaRemoteCommand() != null
                    // Read the real iPhone name from GAP service
                    readGapDeviceName()
                    // Read companion status to notify iPhone app
                    readCompanionStatus()
                    // Subscribe to call state from companion app (if available)
                    val session = gatt
                    scope.launch {
                        delay(500) // Small delay to avoid GATT queue congestion
                        if (gatt !== session) return@launch
                        subscribeToCallState()
                        delay(350)
                        if (gatt !== session) return@launch
                        mediaRemoteCommand()?.let(::subscribeToCharacteristic)
                        mediaEntityUpdate()?.let(::subscribeToCharacteristic)
                    }
                }
            }

            // Process next queued descriptor write
            processDescriptorWriteQueue()
        },
        onCharacteristicWritten = { characteristic, status ->
            if (characteristic.uuid in setOf(draftUUID, toolsUUID, workoutUUID)) draftResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
            if (characteristic.uuid == MEDIA_REMOTE_COMMAND_UUID) {
                mediaCommandResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
            if (characteristic.uuid == AncsConstants.CONTROL_POINT_UUID) {
                if (lastControlCommand?.toInt() == AncsConstants.COMMAND_GET_NOTIFICATION_ATTRIBUTES && status != BluetoothGatt.GATT_SUCCESS) {
                    onAttributeRequestFailed()
                }
                lastControlCommand = null
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: $status for ${characteristic.uuid}")
            }
        },
        onMtuChanged = { mtu, status ->
            if (status == BluetoothGatt.GATT_SUCCESS) mtuBytes = mtu
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            // After MTU negotiation, discover services
            gatt?.discoverServices()
        },
        onCharacteristicReadCallback = { characteristic, value ->
            onCharacteristicRead(characteristic.uuid, value)
        },
        onOtherCharacteristicChanged = { uuid, data ->
            if (uuid.equals(MEDIA_REMOTE_COMMAND_UUID.toString(), ignoreCase = true)) {
                supportedCommands.value = data.map { it.toInt() and 0xff }.toSet()
            }
            if (uuid.equals(MEDIA_ENTITY_UPDATE_UUID.toString(), ignoreCase = true)) {
                _nowPlaying.value = _nowPlaying.value.withTrackUpdate(data)
            }
        },
        onCallStateChanged = { data ->
            _callStateEvents.tryEmit(data)
        }
    )

    /**
     * Connect to a BLE device (iPhone).
     */
    fun connect(device: BluetoothDevice, autoReconnect: Boolean = true) {
        // Guard: skip if already connected or connecting to this device
        val currentState = _connectionState.value
        if (gatt != null && gatt?.device?.address == device.address &&
            (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting)) {
            Log.d(TAG, "Already connected/connecting to ${device.address}, skipping")
            return
        }

        reconnectJob?.cancel()
        this.autoReconnect = autoReconnect
        this.userDisconnected = false
        this.targetDevice = device
        this.serviceDiscoveryRetries = 0

        _connectionState.value = ConnectionState.Connecting(if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone"))

        // A new link starts from scratch: stale characteristics would make it skip the ANCS subscription.
        clearCharacteristics()
        gatt?.close()
        gatt = connectGatt(device, autoConnect = false) // faster initial connection
    }

    /**
     * Reconnect to a previously bonded device using autoConnect.
     */
    fun reconnect(device: BluetoothDevice) {
        // Guard: skip if already connected or connecting to this device
        val currentState = _connectionState.value
        if (gatt != null && gatt?.device?.address == device.address &&
            (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting)) {
            Log.d(TAG, "Already connected/connecting to ${device.address}, skipping reconnect")
            return
        }

        reconnectJob?.cancel()
        this.targetDevice = device
        this.autoReconnect = true
        this.userDisconnected = false
        this.serviceDiscoveryRetries = 0

        _connectionState.value = ConnectionState.Connecting(if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone"))

        clearCharacteristics()
        gatt?.close()
        gatt = connectGatt(device, autoConnect = true) // chipset whitelist, battery efficient
    }

    /**
     * GATT callbacks run on the main thread, like the coroutines that share this
     * class's state (characteristics, descriptor queue, reconnect job).
     * Null (Bluetooth off) is treated like a disconnect.
     */
    private fun connectGatt(device: BluetoothDevice, autoConnect: Boolean): BluetoothGatt? {
        val connection = device.connectGatt(
            context,
            autoConnect,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            Handler(Looper.getMainLooper())
        )
        if (connection == null) {
            Log.w(TAG, "connectGatt failed (Bluetooth off?)")
            _connectionState.value = ConnectionState.Disconnected()
            if (autoReconnect) scheduleReconnect()
        }
        return connection
    }

    /**
     * Bluetooth was turned back on: reconnect right away instead of waiting for the backoff.
     * Falls back to the bonded iPhone when there is no target yet: a service started with
     * Bluetooth off sees no bonded devices, so it never set one.
     */
    fun onBluetoothOn() {
        if (userDisconnected) return
        val device = targetDevice ?: getBondedIPhone() ?: return
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        reconnect(device)
    }

    /** The stack is gone: our BluetoothGatt is dead and no disconnect callback will come. */
    fun onBluetoothOff() {
        reconnectJob?.cancel()
        clearCharacteristics()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Disconnected("Bluetooth off")
    }

    /**
     * Write to the ANCS Control Point characteristic.
     */
    fun writeControlPoint(data: ByteArray): Boolean {
        val gatt = this.gatt ?: return false
        val char = controlPointChar ?: return false

        val queued = writeWithResponse(gatt, char, data)
        if (queued) lastControlCommand = data.firstOrNull()
        return queued
    }

    suspend fun writeAttributeRequest(data: ByteArray): Boolean {
        val session = gatt ?: return false
        repeat(4) { attempt ->
            if (gatt !== session) return false
            if (writeControlPoint(data)) return true
            if (attempt < 3) delay(100)
        }
        return false
    }

    private fun mediaRemoteCommand(): BluetoothGattCharacteristic? =
        gatt?.getService(MEDIA_SERVICE_UUID)?.getCharacteristic(MEDIA_REMOTE_COMMAND_UUID)

    private fun mediaEntityUpdate(): BluetoothGattCharacteristic? =
        gatt?.getService(MEDIA_SERVICE_UUID)?.getCharacteristic(MEDIA_ENTITY_UPDATE_UUID)

    suspend fun sendMediaCommand(command: Byte): Boolean {
        if (mediaCommandResult != null) return false
        val session = gatt ?: return false
        val characteristic = mediaRemoteCommand() ?: return false
        val result = CompletableDeferred<Boolean>()
        mediaCommandResult = result
        try {
            repeat(4) { attempt ->
                if (gatt !== session) return false
                if (writeWithResponse(session, characteristic, byteArrayOf(command))) {
                    return withTimeoutOrNull(2_000) { result.await() } ?: false
                }
                if (attempt < 3) delay(100)
            }
            return false
        } finally {
            if (mediaCommandResult === result) mediaCommandResult = null
        }
    }

    private fun writeWithResponse(
        session: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean = session.writeCharacteristic(
        characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ) == BluetoothStatusCodes.SUCCESS

    /** Cached real device name read from GAP characteristic */
    private var cachedDeviceName: String? = null

    /**
     * Resolve a user-friendly device name from the bonded device list or GATT connection.
     * BLE advertised names are often truncated (e.g., "AncsBrid" instead of "iPhone").
     */
    private fun resolveDeviceName(): String {
        // Return cached GAP name if available
        cachedDeviceName?.let { return it }

        // Check bonded devices for a full name
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val bondedName = adapter?.bondedDevices?.firstOrNull { device ->
            device.address == gatt?.device?.address
        }?.name

        // Prefer the bonded name, fall back to GATT device name
        val rawName = bondedName ?: gatt?.device?.name ?: "iPhone"

        // If the name is a truncated BLE name, show "iPhone" instead
        return if (isTruncatedBleName(rawName)) "iPhone" else rawName
    }

    private fun isTruncatedBleName(name: String): Boolean {
        return name.startsWith("AncsBrid", ignoreCase = true) ||
            name.startsWith("AppleToWe", ignoreCase = true) ||
            name.startsWith("WearBrid", ignoreCase = true) ||
            name.startsWith("WearBridge", ignoreCase = true)
    }

    /**
     * Read the real device name from the GAP (Generic Access Profile) service.
     * The Device Name characteristic (0x2A00) contains the actual iPhone name
     * (e.g., "Karthik's iPhone Max") rather than the truncated BLE advertising name.
     */
    fun readGapDeviceName() {
        val gatt = this.gatt ?: return
        val gapServiceUuid = java.util.UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val deviceNameUuid = java.util.UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        val service = gatt.getService(gapServiceUuid) ?: return
        val char = service.getCharacteristic(deviceNameUuid) ?: return
        gatt.readCharacteristic(char)
        Log.i(TAG, "Reading GAP Device Name characteristic")
    }

    /**
     * Called from GattCallback when a characteristic read completes.
     * If it's the GAP Device Name, cache and broadcast it.
     */
    fun onCharacteristicRead(characteristicUuid: java.util.UUID, value: ByteArray) {
        val deviceNameUuid = java.util.UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        if (characteristicUuid == toolsUUID) {
            phoneSummary.value = value.decodeToString().take(512)
            return
        }
        if (characteristicUuid == deviceNameUuid) {
            val name = String(value, Charsets.UTF_8)
            if (name.isNotBlank() && !isTruncatedBleName(name)) {
                cachedDeviceName = name
                Log.i(TAG, "GAP Device Name: $name")
                _connectionState.value = ConnectionState.Connected(name)
                onDeviceNameResolved?.invoke(name)
            }
        }
    }

    /** Callback to notify the UI when the real device name is resolved */
    var onDeviceNameResolved: ((String) -> Unit)? = null

    /**
     * Read the companion app's status characteristic to notify the iPhone app
     * that the watch has connected. This triggers didReceiveRead on the iOS side.
     */
    private fun readCompanionStatus() {
        val gatt = this.gatt ?: return
        val companionServiceUuid = java.util.UUID.fromString("A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        val statusCharUuid = java.util.UUID.fromString("A0E3D5B2-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        val service = gatt.getService(companionServiceUuid)
        if (service != null) {
            val char = service.getCharacteristic(statusCharUuid)
            if (char != null) {
                gatt.readCharacteristic(char)
                Log.i(TAG, "Reading companion status characteristic to notify iPhone app")
            } else {
                Log.d(TAG, "Companion status characteristic not found")
            }
        } else {
            Log.d(TAG, "Companion service not found — iPhone companion app may not be running")
        }
    }

    /**
     * Subscribe to the call state characteristic from the iOS companion app.
     * When enabled, the companion pushes real-time call state (ringing/active/ended).
     */
    fun subscribeToCallState() {
        val gatt = this.gatt ?: return
        val service = gatt.getService(AncsConstants.COMPANION_SERVICE_UUID) ?: run {
            Log.d(TAG, "subscribeToCallState: companion service not found")
            return
        }
        val char = service.getCharacteristic(AncsConstants.CALL_STATE_CHARACTERISTIC_UUID) ?: run {
            Log.d(TAG, "subscribeToCallState: call state characteristic not found")
            return
        }
        subscribeToCharacteristic(char)
        Log.i(TAG, "Subscribing to call state characteristic")
    }

    /**
     * Write quick reply payload to the iPhone companion app via BLE.
     * Format: "callerName|replyMessage"
     */
    fun writeQuickReply(payload: String) {
        val gatt = this.gatt ?: run {
            Log.e(TAG, "writeQuickReply: no GATT connection")
            return
        }
        val service = gatt.getService(AncsConstants.COMPANION_SERVICE_UUID) ?: run {
            Log.e(TAG, "writeQuickReply: companion service not found")
            return
        }
        val char = service.getCharacteristic(AncsConstants.QUICK_REPLY_CHARACTERISTIC_UUID) ?: run {
            Log.e(TAG, "writeQuickReply: quick reply characteristic not found")
            return
        }
        val data = payload.toByteArray(Charsets.UTF_8)
        val success = writeWithResponse(gatt, char, data)
        Log.i(TAG, "writeQuickReply: wrote ${data.size} bytes, success=$success")
    }

    fun disconnect() {
        autoReconnect = false
        userDisconnected = true
        reconnectJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        clearCharacteristics()
        targetDevice = null
        _connectionState.value = ConnectionState.Idle
    }

    /**
     * Get the bonded iPhone device if one exists.
     */
    fun getBondedIPhone(): BluetoothDevice? {
        savedIPhone(context)?.let { return it }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return null
        return adapter.bondedDevices?.firstOrNull { device ->
            val name = device.name ?: ""
            name.contains("iPhone", ignoreCase = true) ||
                name.contains("AncsBrid", ignoreCase = true) ||
                name.contains("AncsBridge", ignoreCase = true) ||
                name.contains("AppleToWe", ignoreCase = true) ||
                name.contains("AppleToWearOS", ignoreCase = true) ||
                name.contains("WearBridge", ignoreCase = true) ||
                name.contains("WearBrid", ignoreCase = true)
        }
    }

    private fun subscribeToCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = this.gatt ?: return

        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(AncsConstants.CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
            return
        }

        // Queue the write since BLE only allows one outstanding GATT operation at a time
        descriptorWriteQueue.add(characteristic to descriptor)
        if (!isWritingDescriptor) {
            processDescriptorWriteQueue()
        }
    }

    private fun processDescriptorWriteQueue() {
        if (isWritingDescriptor || descriptorWriteQueue.isEmpty()) return
        val (_, descriptor) = descriptorWriteQueue.poll() ?: return
        isWritingDescriptor = true
        if (gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            != BluetoothStatusCodes.SUCCESS) {
            isWritingDescriptor = false
            when (descriptor.characteristic.uuid) {
                AncsConstants.DATA_SOURCE_UUID, AncsConstants.NOTIFICATION_SOURCE_UUID ->
                    stopAfterDiscoveryFailure("Could not subscribe to iPhone notifications")
                else -> processDescriptorWriteQueue()
            }
        }
    }

    private fun stopAfterDiscoveryFailure(message: String) {
        reconnectJob?.cancel()
        gatt?.close()
        gatt = null
        clearCharacteristics()
        _connectionState.value = ConnectionState.Error(message)
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Scheduling reconnect in ${reconnectDelay}ms")
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            targetDevice?.let { reconnect(it) }
        }
    }

    private fun clearCharacteristics() {
        onSessionReset()
        lastControlCommand = null
        draftResult?.complete(false)
        draftResult = null
        mtuBytes = 23
        phoneTools.value = false
        phoneSummary.value = ""
        supportedCommands.value = null
        _mediaAvailable.value = false
        _nowPlaying.value = NowPlaying()
        mediaCommandResult?.complete(false)
        mediaCommandResult = null
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        descriptorWriteQueue.clear()
        isWritingDescriptor = false
    }

    fun destroy() {
        autoReconnect = false
        reconnectJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
