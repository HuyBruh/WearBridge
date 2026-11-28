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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Manages the BLE GATT connection to an iPhone: ANCS (notifications), AMS (media) and
 * the optional WearBridge companion service.
 *
 * Android allows exactly one outstanding GATT operation per connection. Every read,
 * write and CCCD write therefore goes through [runOperation], a FIFO queue that waits
 * for the matching callback before starting the next operation. Issuing operations
 * concurrently makes the stack answer "busy" and silently drops attribute requests,
 * which shows up as missing notifications.
 *
 * Flow: connect → MTU → discover → (bond) → subscribe DS → subscribe NS → active
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "BleConnectionManager"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val TARGET_MTU = 512
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 3
        private const val OPERATION_TIMEOUT_MS = 5_000L
        private const val START_ATTEMPTS = 5
        private const val PREFS_NAME = "wearbridge"
        private const val KEY_PAIRED_IPHONE = "paired_iphone_address"

        // Apple Media Service
        private val MEDIA_SERVICE_UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
        private val MEDIA_REMOTE_COMMAND_UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
        private val MEDIA_ENTITY_UPDATE_UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")

        // Generic Access
        private val GAP_SERVICE_UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val DEVICE_NAME_UUID = UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")

        // WearBridge companion (iOS app) characteristics
        private val STATUS_UUID = UUID.fromString("A0E3D5B2-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        private val DRAFT_UUID = UUID.fromString("A0E3D5B5-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        private val TOOLS_UUID = UUID.fromString("A0E3D5B6-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        /** Chunked health records for Apple Health (workouts, heart rate, steps). */
        private val HEALTH_UUID = UUID.fromString("A0E3D5B8-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        /** JSON array of the quick replies edited in the iPhone app. */
        private val REPLIES_UUID = UUID.fromString("A0E3D5B9-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        /** Weather / calendar / reminders JSON; notify = "re-read me"; write = complete reminder. */
        private val INFO_UUID = UUID.fromString("A0E3D5BA-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        /**
         * Album artwork (AMS has none): write {"t","a"} to ask, notify 0x01 = ready / 0x00 = none,
         * then write a 2-byte chunk index and read [count hi, count lo][JPEG bytes…].
         */
        private val ARTWORK_UUID = UUID.fromString("A0E3D5BB-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        private const val CHUNK_FIRST = 0x01
        private const val CHUNK_LAST = 0x02

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

        /** Disconnect reasons that mean the iPhone no longer has our pairing keys. */
        private val PAIRING_LOST = setOf(0x05, 0x06, 0x3D)

        /**
         * Removes a bond. removeBond() is hidden API: it works on many builds, but newer
         * Android may block it, in which case the user removes the device in Settings.
         */
        fun removeBond(device: BluetoothDevice): Boolean =
            runCatching { device.javaClass.getMethod("removeBond").invoke(device) as Boolean }
                .onFailure { Log.w(TAG, "removeBond blocked", it) }
                .getOrDefault(false)

        fun clearSavedIPhone(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_PAIRED_IPHONE).apply()
        }

        fun looksLikeIPhone(name: String): Boolean = listOf(
            "iPhone", "AncsBrid", "AppleToWe", "WearBrid"
        ).any { name.contains(it, ignoreCase = true) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Android delivers these on the main handler, in order across both characteristics.
    var onNotificationEvent: (ByteArray) -> Unit = {}
    var onAttributeData: (ByteArray) -> Unit = {}
    /** The ANCS session ended: notification UIDs from it are no longer valid. */
    var onSessionReset: () -> Unit = {}
    var onDeviceNameResolved: ((String) -> Unit)? = null

    private val _callStateEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val callStateEvents: SharedFlow<ByteArray> = _callStateEvents.asSharedFlow()

    val supportedCommands = MutableStateFlow<Set<Int>?>(null)
    private val _mediaAvailable = MutableStateFlow(false)
    val mediaAvailable: StateFlow<Boolean> = _mediaAvailable.asStateFlow()
    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    val phoneTools = MutableStateFlow(false)
    /** Latest Today data from the iPhone app (JSON), empty until read. */
    val phoneInfo = MutableStateFlow("")
    val phoneSummary = MutableStateFlow("")

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var reconnectJob: Job? = null
    private var sessionJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var autoReconnect = true
    /** The user chose Disconnect: Bluetooth coming back on must not reconnect. */
    private var userDisconnected = false
    private var serviceDiscoveryRetries = 0
    private var repairAttempted = false
    /** True when the iPhone forgot this watch and the stale bond could not be removed. */
    val pairingLost = MutableStateFlow(false)

    /** The iPhone forgot us: remove the stale bond and pair again, or ask the user to. */
    private fun handlePairingLost(device: BluetoothDevice) {
        Log.w(TAG, "iPhone no longer has our pairing keys")
        reconnectJob?.cancel()
        if (removeBond(device)) {
            _connectionState.value = ConnectionState.Bonding(displayName(device))
            scope.launch {
                // Wait for the bond to clear, then connect: discovery will call createBond().
                repeat(20) {
                    if (device.bondState == BluetoothDevice.BOND_NONE) return@repeat
                    delay(250)
                }
                connect(device)
            }
        } else {
            autoReconnect = false
            pairingLost.value = true
            _connectionState.value = ConnectionState.Error(
                "iPhone forgot this watch. Remove the iPhone in watch Settings › Connectivity › Bluetooth, then pair again."
            )
        }
    }
    private var mtuBytes = 23
    private var cachedDeviceName: String? = null

    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    // ---- GATT operation queue -------------------------------------------------------

    private class PendingOperation(val uuid: UUID) {
        val result = CompletableDeferred<Pair<Int, ByteArray?>>()
    }

    private val operationLock = Mutex()
    private var pendingOperation: PendingOperation? = null

    /**
     * Run one GATT operation and wait for its callback. [start] issues the request and
     * returns true if the stack accepted it. Returns (status, value) or null if the
     * connection changed, the stack never accepted the request, or it timed out.
     */
    private suspend fun runOperation(
        session: BluetoothGatt,
        uuid: UUID,
        start: () -> Boolean
    ): Pair<Int, ByteArray?>? = operationLock.withLock {
        repeat(START_ATTEMPTS) { attempt ->
            if (session !== gatt) return@withLock null
            val operation = PendingOperation(uuid)
            pendingOperation = operation
            val started = try { start() } catch (e: Exception) {
                Log.w(TAG, "GATT request threw for $uuid", e); false
            }
            if (started) {
                val result = withTimeoutOrNull(OPERATION_TIMEOUT_MS) { operation.result.await() }
                if (pendingOperation === operation) pendingOperation = null
                if (result == null) Log.w(TAG, "GATT operation timed out: $uuid")
                return@withLock result
            }
            // Another component (system, other app) may hold the stack briefly.
            if (pendingOperation === operation) pendingOperation = null
            delay(40L * (attempt + 1))
        }
        Log.w(TAG, "GATT request not accepted: $uuid")
        null
    }

    private fun completeOperation(uuid: UUID, status: Int, value: ByteArray?) {
        val operation = pendingOperation ?: return
        if (operation.uuid == uuid) {
            pendingOperation = null
            operation.result.complete(status to value)
        }
    }

    private fun failPendingOperation() {
        pendingOperation?.result?.complete(BluetoothGatt.GATT_FAILURE to null)
        pendingOperation = null
    }

    private suspend fun write(
        session: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        val result = runOperation(session, characteristic.uuid) {
            session.writeCharacteristic(
                characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        }
        return result?.first == BluetoothGatt.GATT_SUCCESS
    }

    private suspend fun read(session: BluetoothGatt, characteristic: BluetoothGattCharacteristic): ByteArray? {
        val result = runOperation(session, characteristic.uuid) { session.readCharacteristic(characteristic) }
        return if (result?.first == BluetoothGatt.GATT_SUCCESS) result.second else null
    }

    private suspend fun enableNotifications(
        session: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val descriptor = characteristic.getDescriptor(AncsConstants.CCCD_UUID) ?: run {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
            return false
        }
        if (!session.setCharacteristicNotification(characteristic, true)) return false
        val result = runOperation(session, characteristic.uuid) {
            session.writeDescriptor(
                descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        }
        return result?.first == BluetoothGatt.GATT_SUCCESS
    }

    // ---- Bonding / callbacks --------------------------------------------------------

    // Bond events come for every device the watch pairs with (earbuds...): only the iPhone's matter.
    private fun isTarget(device: BluetoothDevice) = device.address == targetDevice?.address

    val bondStateReceiver = BondStateReceiver(
        onBonded = { device ->
            if (!isTarget(device)) return@BondStateReceiver
            Log.i(TAG, "Bond completed, re-discovering services")
            val session = gatt
            scope.launch {
                delay(1000)
                if (session != null && session === gatt) session.discoverServices()
            }
        },
        onBondFailed = { device ->
            if (!isTarget(device)) return@BondStateReceiver
            Log.e(TAG, "Bond failed")
            disconnect()
            _connectionState.value = ConnectionState.Error("Pairing failed. Try again.")
        }
    )

    private val gattCallback = GattCallback(
        isCurrentConnection = { it === gatt },
        onConnected = { session ->
            _connectionState.value = ConnectionState.Connecting(displayName(session.device))
            // Larger MTU = fewer Data Source fragments = faster notifications.
            if (!session.requestMtu(TARGET_MTU)) session.discoverServices()
        },
        onDisconnected = { session, status ->
            Log.i(TAG, "Disconnected, status=$status")
            endSession()
            session.close()
            gatt = null
            if (status in PAIRING_LOST && session.device.bondState == BluetoothDevice.BOND_BONDED) {
                handlePairingLost(session.device)
                return@GattCallback
            }
            // Keep an actionable error (e.g. "turn on Share System Notifications") on screen.
            if (_connectionState.value !is ConnectionState.Error) {
                _connectionState.value = ConnectionState.Disconnected()
            }
            if (autoReconnect && targetDevice != null) scheduleReconnect()
        },
        onServicesDiscoveredCallback = ::handleServicesDiscovered,
        onServiceChangedCallback = { session ->
            // The companion app started or stopped (its GATT service appeared/vanished).
            // Characteristic handles are now stale: start a fresh link right away.
            Log.i(TAG, "iPhone GATT database changed, reconnecting")
            reconnectDelay = 500L
            session.disconnect()
        },
        onMtuChanged = { mtu, status ->
            if (status == BluetoothGatt.GATT_SUCCESS) mtuBytes = mtu
            gatt?.discoverServices()
        },
        onOperationComplete = ::completeOperation,
        onCharacteristicChanged = ::routeCharacteristicData,
    )

    private fun routeCharacteristicData(uuid: UUID, data: ByteArray) {
        when (uuid) {
            AncsConstants.NOTIFICATION_SOURCE_UUID -> onNotificationEvent(data)
            AncsConstants.DATA_SOURCE_UUID -> onAttributeData(data)
            AncsConstants.CALL_STATE_CHARACTERISTIC_UUID -> _callStateEvents.tryEmit(data)
            INFO_UUID -> scope.launch { refreshInfo() }
            ARTWORK_UUID -> if (data.firstOrNull() == 1.toByte()) scope.launch { downloadArtwork() }
                else _artwork.value = null
            MEDIA_REMOTE_COMMAND_UUID -> supportedCommands.value = data.map { it.toInt() and 0xff }.toSet()
            MEDIA_ENTITY_UPDATE_UUID -> {
                val before = _nowPlaying.value
                val now = before.withUpdate(data)
                _nowPlaying.value = now
                if (now.title != before.title || now.artist != before.artist) {
                    artworkJob?.cancel()
                    // Title and artist arrive as separate updates: wait for both.
                    artworkJob = scope.launch { delay(600); requestArtwork(now.title, now.artist) }
                }
            }
        }
    }

    private fun handleServicesDiscovered(session: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Service discovery failed: $status")
            // Drop the link so the normal reconnect starts over.
            session.disconnect()
            return
        }

        val ancsService = session.getService(AncsConstants.ANCS_SERVICE_UUID)
        if (ancsService == null) {
            if (session.device.bondState != BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "ANCS not visible yet, starting pairing")
                _connectionState.value = ConnectionState.Bonding(displayName(session.device))
                session.device.createBond()
                return
            }
            // Bonded, but iOS hides ANCS: either it publishes it a moment later, or
            // "Share System Notifications" is off for this watch in iPhone Bluetooth settings.
            serviceDiscoveryRetries++
            if (serviceDiscoveryRetries <= MAX_SERVICE_DISCOVERY_RETRIES) {
                Log.w(TAG, "ANCS unavailable; retry $serviceDiscoveryRetries/$MAX_SERVICE_DISCOVERY_RETRIES")
                scope.launch {
                    delay(2_000)
                    if (gatt === session && !session.discoverServices()) session.disconnect()
                }
            } else if (!repairAttempted) {
                // Most often the iPhone forgot this watch while the watch kept its keys:
                // drop our stale bond and pair again (iOS then asks to pair).
                repairAttempted = true
                session.disconnect()
                handlePairingLost(session.device)
            } else {
                _connectionState.value = ConnectionState.Error(
                    "On iPhone: Settings › Bluetooth › ⓘ next to this watch › turn on Share System Notifications"
                )
                // Keep trying in the background with backoff: the toggle may be flipped later.
                session.disconnect()
            }
            return
        }

        serviceDiscoveryRetries = 0
        val ns = ancsService.getCharacteristic(AncsConstants.NOTIFICATION_SOURCE_UUID)
        val cp = ancsService.getCharacteristic(AncsConstants.CONTROL_POINT_UUID)
        val ds = ancsService.getCharacteristic(AncsConstants.DATA_SOURCE_UUID)
        if (ns == null || cp == null || ds == null) {
            Log.e(TAG, "Missing ANCS characteristics")
            session.disconnect()
            return
        }
        // Rediscovery on an already-active session (e.g. after bonding): nothing to redo.
        if (sessionJob?.isActive == true && notificationSourceChar != null) return

        notificationSourceChar = ns
        controlPointChar = cp
        dataSourceChar = ds
        sessionJob?.cancel()
        sessionJob = scope.launch { startSession(session, ns, ds) }
    }

    private suspend fun startSession(
        session: BluetoothGatt,
        ns: BluetoothGattCharacteristic,
        ds: BluetoothGattCharacteristic
    ) {
        // Apple: subscribe to Data Source before Notification Source.
        if (!enableNotifications(session, ds) || !enableNotifications(session, ns)) {
            Log.e(TAG, "Could not subscribe to ANCS, restarting link")
            if (gatt === session) session.disconnect()
            return
        }
        Log.i(TAG, "ANCS session active")
        repairAttempted = false
        directTried = false
        pairingLost.value = false
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PAIRED_IPHONE, session.device.address).apply()
        if (targetDevice != null) autoReconnect = true
        _connectionState.value = ConnectionState.Connected(resolveDeviceName(session))

        // Everything below is optional and queued behind live attribute requests.
        session.getService(GAP_SERVICE_UUID)?.getCharacteristic(DEVICE_NAME_UUID)?.let { char ->
            read(session, char)?.decodeToString()?.trim()?.takeIf {
                it.isNotBlank() && !isTruncatedBleName(it)
            }?.let { name ->
                cachedDeviceName = name
                if (gatt === session) _connectionState.value = ConnectionState.Connected(name)
                onDeviceNameResolved?.invoke(name)
            }
        }

        val companion = session.getService(AncsConstants.COMPANION_SERVICE_UUID)
        if (companion != null) {
            // Reading status tells the iPhone app a watch is connected.
            companion.getCharacteristic(STATUS_UUID)?.let { read(session, it) }
            companion.getCharacteristic(AncsConstants.CALL_STATE_CHARACTERISTIC_UUID)
                ?.let { enableNotifications(session, it) }
            companion.getCharacteristic(REPLIES_UUID)?.let { char ->
                read(session, char)?.decodeToString()?.let {
                    com.wearos.ancsbridge.model.QuickReplies.save(context, it)
                }
            }
            companion.getCharacteristic(INFO_UUID)?.let { char ->
                enableNotifications(session, char)
                read(session, char)?.decodeToString()?.let { phoneInfo.value = it }
            }
            phoneTools.value = companion.getCharacteristic(TOOLS_UUID) != null
            companion.getCharacteristic(ARTWORK_UUID)?.let { enableNotifications(session, it) }
        }

        val media = session.getService(MEDIA_SERVICE_UUID)
        val remote = media?.getCharacteristic(MEDIA_REMOTE_COMMAND_UUID)
        val entity = media?.getCharacteristic(MEDIA_ENTITY_UPDATE_UUID)
        if (remote != null && entity != null) {
            enableNotifications(session, remote)
            if (enableNotifications(session, entity)) {
                // One write per entity: player state, then track metadata.
                write(session, entity, NowPlaying.playerSubscription())
                write(session, entity, NowPlaying.trackSubscription())
            }
            _mediaAvailable.value = gatt === session
        }
    }

    /** Clears everything that belongs to one GATT link. */
    private fun endSession() {
        sessionJob?.cancel()
        sessionJob = null
        failPendingOperation()
        onSessionReset()
        mtuBytes = 23
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        phoneTools.value = false
        phoneSummary.value = ""
        supportedCommands.value = null
        _mediaAvailable.value = false
        _nowPlaying.value = NowPlaying()
        artworkJob?.cancel()
        requestedArtworkKey = ""
        _artwork.value = null
    }

    // ---- Connection lifecycle -------------------------------------------------------

    /** Connect to an iPhone chosen by the user (fast direct connection). */
    fun connect(device: BluetoothDevice, autoReconnect: Boolean = true) {
        if (isActiveLinkTo(device)) return
        reconnectJob?.cancel()
        this.autoReconnect = autoReconnect
        userDisconnected = false
        targetDevice = device
        serviceDiscoveryRetries = 0
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        openLink(device, autoConnect = false)
    }

    /**
     * Reconnect to a bonded iPhone. The first try after losing the link (or when the app is
     * opened, [fast]) is a direct connection, which comes up in a second or two when the
     * iPhone is near; after that, autoConnect lets the controller wait for it efficiently.
     */
    fun reconnect(device: BluetoothDevice, fast: Boolean = false) {
        val waitingInBackground = linkIsAuto && _connectionState.value !is ConnectionState.Connected
        if (isActiveLinkTo(device) && !(fast && waitingInBackground)) return
        reconnectJob?.cancel()
        targetDevice = device
        autoReconnect = true
        userDisconnected = false
        serviceDiscoveryRetries = 0
        val direct = fast || !directTried
        directTried = true
        if (fast) reconnectDelay = 1_000L
        openLink(device, autoConnect = !direct)
    }

    /** True while the link is a background (autoConnect) wait rather than a direct attempt. */
    private var linkIsAuto = false
    private var directTried = false

    private fun isActiveLinkTo(device: BluetoothDevice): Boolean {
        val state = _connectionState.value
        return gatt?.device?.address == device.address &&
            (state is ConnectionState.Connected || state is ConnectionState.Connecting ||
                state is ConnectionState.Bonding)
    }

    private fun openLink(device: BluetoothDevice, autoConnect: Boolean) {
        linkIsAuto = autoConnect
        endSession()
        gatt?.close()
        gatt = null
        // Background retries keep an error visible until a link actually comes up.
        if (!(autoConnect && _connectionState.value is ConnectionState.Error)) {
            _connectionState.value = ConnectionState.Connecting(displayName(device))
        }
        // Callbacks on the main looper, like every coroutine touching this state.
        val connection = device.connectGatt(
            context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper())
        )
        if (connection == null) {
            Log.w(TAG, "connectGatt failed (Bluetooth off?)")
            _connectionState.value = ConnectionState.Disconnected()
            if (this.autoReconnect) scheduleReconnect()
        }
        gatt = connection
    }

    /** Bluetooth was turned back on: reconnect right away instead of waiting for the backoff. */
    fun onBluetoothOn() {
        if (userDisconnected) return
        val device = targetDevice ?: getBondedIPhone() ?: return
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        reconnect(device)
    }

    /** The stack is gone: our BluetoothGatt is dead and no disconnect callback will come. */
    fun onBluetoothOff() {
        reconnectJob?.cancel()
        endSession()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Disconnected("Bluetooth off")
    }

    fun disconnect() {
        autoReconnect = false
        userDisconnected = true
        reconnectJob?.cancel()
        endSession()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        targetDevice = null
        _connectionState.value = ConnectionState.Idle
    }

    fun destroy() {
        autoReconnect = false
        reconnectJob?.cancel()
        endSession()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val wait = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectJob = scope.launch {
            Log.i(TAG, "Reconnecting in ${wait}ms")
            delay(wait)
            targetDevice?.let { reconnect(it) }
        }
    }

    // ---- ANCS / AMS / companion requests --------------------------------------------

    /** Write to the ANCS Control Point and wait for the iPhone to accept it. */
    suspend fun writeAttributeRequest(data: ByteArray): Boolean {
        val session = gatt ?: return false
        val char = controlPointChar ?: return false
        return write(session, char, data)
    }

    suspend fun sendMediaCommand(command: Byte): Boolean {
        val session = gatt ?: return false
        val char = session.getService(MEDIA_SERVICE_UUID)?.getCharacteristic(MEDIA_REMOTE_COMMAND_UUID)
            ?: return false
        return write(session, char, byteArrayOf(command))
    }

    private fun companionCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(AncsConstants.COMPANION_SERVICE_UUID)?.getCharacteristic(uuid)

    val canDraft: Boolean get() = companionCharacteristic(DRAFT_UUID) != null

    private suspend fun companionWrite(uuid: UUID, data: ByteArray): Boolean {
        val session = gatt ?: return false
        val char = companionCharacteristic(uuid) ?: return false
        if (data.size > minOf(512, mtuBytes - 3)) return false
        return write(session, char, data)
    }

    suspend fun saveDraft(data: ByteArray) = companionWrite(DRAFT_UUID, data)

    /** 1 = ping iPhone, 2 = camera shutter. */
    suspend fun phoneCommand(code: Int): Boolean =
        code in 1..2 && companionWrite(TOOLS_UUID, byteArrayOf(code.toByte()))

    /** Reads the battery & agenda summary published by the iPhone app. */
    suspend fun refreshPhone(): Boolean {
        val session = gatt ?: return false
        val char = companionCharacteristic(TOOLS_UUID) ?: return false
        val value = read(session, char) ?: return false
        phoneSummary.value = value.decodeToString().take(512)
        return true
    }

    val canSyncHealth: Boolean get() = companionCharacteristic(HEALTH_UUID) != null

    /**
     * Sends one health record as a sequence of acknowledged chunks:
     * [flags][payload…], flags bit0 = first chunk, bit1 = last chunk. The iPhone answers
     * the last chunk only after the record is safely stored, so `true` means delivered.
     */
    suspend fun sendHealthRecord(data: ByteArray): Boolean {
        val session = gatt ?: return false
        val char = companionCharacteristic(HEALTH_UUID) ?: return false
        val chunkSize = (minOf(512, mtuBytes - 3) - 1).coerceAtLeast(19)
        var offset = 0
        while (true) {
            val end = minOf(offset + chunkSize, data.size)
            var flags = 0
            if (offset == 0) flags = flags or CHUNK_FIRST
            if (end == data.size) flags = flags or CHUNK_LAST
            val chunk = byteArrayOf(flags.toByte()) + data.copyOfRange(offset, end)
            if (!write(session, char, chunk)) return false
            if (end == data.size) return true
            offset = end
        }
    }

    /** Re-reads weather, calendar and reminders from the iPhone app. */
    suspend fun refreshInfo(): Boolean {
        val session = gatt ?: return false
        val char = companionCharacteristic(INFO_UUID) ?: return false
        val value = read(session, char) ?: return false
        phoneInfo.value = value.decodeToString()
        return true
    }

    // ---- Album artwork --------------------------------------------------------------

    private val _artwork = MutableStateFlow<android.graphics.Bitmap?>(null)
    /** Artwork of the song playing on the iPhone, or null. */
    val artwork: StateFlow<android.graphics.Bitmap?> = _artwork.asStateFlow()
    private var artworkKey = ""
    private var artworkJob: kotlinx.coroutines.Job? = null
    private var requestedArtworkKey = ""
    private val artworkCache = android.util.LruCache<String, android.graphics.Bitmap>(8)

    /** Asks the iPhone app for the artwork of [title] by [artist]; cached per song. */
    suspend fun requestArtwork(title: String, artist: String) {
        val key = "$title\u0000$artist"
        if (title.isBlank()) { artworkKey = ""; _artwork.value = null; return }
        artworkCache.get(key)?.let { artworkKey = key; _artwork.value = it; return }
        if (key == requestedArtworkKey) return
        if (companionCharacteristic(ARTWORK_UUID) == null) return
        _artwork.value = null
        requestedArtworkKey = key
        val request = org.json.JSONObject().put("t", title.take(120)).put("a", artist.take(120)).toString()
        if (!companionWrite(ARTWORK_UUID, request.toByteArray())) requestedArtworkKey = ""
    }

    private suspend fun downloadArtwork() {
        val session = gatt ?: return
        val char = companionCharacteristic(ARTWORK_UUID) ?: return
        val key = requestedArtworkKey
        val out = java.io.ByteArrayOutputStream()
        var index = 0
        var count = 1
        while (index < count && index < 200) {
            if (!write(session, char, byteArrayOf((index shr 8).toByte(), index.toByte()))) return
            val chunk = read(session, char) ?: return
            if (chunk.size < 2) return
            count = ((chunk[0].toInt() and 0xff) shl 8) or (chunk[1].toInt() and 0xff)
            out.write(chunk, 2, chunk.size - 2)
            index++
        }
        val bytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        artworkCache.put(key, bitmap)
        if (key == requestedArtworkKey) { artworkKey = key; _artwork.value = bitmap }
        Log.i(TAG, "Artwork ${bitmap.width}x${bitmap.height}, ${bytes.size} bytes")
    }

    /** Marks a reminder done on the iPhone. */
    suspend fun completeReminder(id: String): Boolean =
        companionWrite(INFO_UUID, org.json.JSONObject().put("complete", id).toString().toByteArray())

    /** Quick reply payload "callerName|replyText" for the iPhone app's Shortcut. */
    suspend fun writeQuickReply(payload: String): Boolean =
        companionWrite(AncsConstants.QUICK_REPLY_CHARACTERISTIC_UUID, payload.toByteArray(Charsets.UTF_8))

    // ---- Names ----------------------------------------------------------------------

    private fun displayName(device: BluetoothDevice): String {
        val name = device.name.orEmpty()
        return if (name.isBlank() || isTruncatedBleName(name)) "iPhone" else name
    }

    private fun resolveDeviceName(session: BluetoothGatt): String =
        cachedDeviceName ?: displayName(session.device)

    private fun isTruncatedBleName(name: String): Boolean =
        listOf("AncsBrid", "AppleToWe", "WearBrid").any { name.startsWith(it, ignoreCase = true) }

    /** The bonded iPhone, preferring the one that last gave us an ANCS session. */
    fun getBondedIPhone(): BluetoothDevice? {
        savedIPhone(context)?.let { return it }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return null
        return adapter.bondedDevices?.firstOrNull { looksLikeIPhone(it.name.orEmpty()) }
    }
}
