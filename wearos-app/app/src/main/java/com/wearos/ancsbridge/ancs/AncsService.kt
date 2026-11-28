package com.wearos.ancsbridge.ancs

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wearos.ancsbridge.AncsApplication
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ble.BleConnectionManager
import com.wearos.ancsbridge.ble.BleScanner
import com.wearos.ancsbridge.ble.BondStateReceiver
import com.wearos.ancsbridge.ble.MediaCommand
import com.wearos.ancsbridge.ble.NowPlaying
import com.wearos.ancsbridge.model.AncsEvent
import com.wearos.ancsbridge.model.AncsNotification
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.ui.IncomingCallActivity
import com.wearos.ancsbridge.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that manages the BLE connection to iPhone and ANCS notification pipeline.
 *
 * Pipeline: BLE scan → connect → bond → subscribe → receive NS events →
 *           fetch attributes via CP → parse DS response → post Android notification
 */
@SuppressLint("MissingPermission")
class AncsService : Service() {

    companion object {
        private const val TAG = "AncsService"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val MIRROR_TAG = "iPhone"

        const val ACTION_START = "com.wearos.ancsbridge.START"
        const val ACTION_STOP = "com.wearos.ancsbridge.STOP"
        const val ACTION_CONNECT = "com.wearos.ancsbridge.CONNECT"
        const val ACTION_DISCONNECT = "com.wearos.ancsbridge.DISCONNECT"
        const val ACTION_RECONNECT = "com.wearos.ancsbridge.RECONNECT"
        const val ACTION_PERFORM_NOTIFICATION_ACTION = "com.wearos.ancsbridge.PERFORM_ACTION"
        const val ACTION_SEND_QUICK_REPLY = "com.wearos.ancsbridge.QUICK_REPLY"
        const val ACTION_MEDIA_COMMAND = "com.wearos.ancsbridge.MEDIA_COMMAND"
        const val EXTRA_MEDIA_COMMAND = "media_command"
        const val ACTION_DRAFT = "com.wearos.ancsbridge.DRAFT"
        const val ACTION_CLEAR_ALL = "com.wearos.ancsbridge.CLEAR_ALL"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_QUICK_REPLY_TEXT = "quick_reply_text"
        const val NOTIFICATION_ID_CALL = 999
        private const val MAX_ACTIVE_NOTIFICATIONS = 20 // Android limit is 25; keep headroom

        /** Shared connection state observable by the ViewModel */
        private val _sharedConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val sharedConnectionState: StateFlow<ConnectionState> = _sharedConnectionState.asStateFlow()
        private val _mediaAvailable = MutableStateFlow(false)
        val mediaAvailable: StateFlow<Boolean> = _mediaAvailable.asStateFlow()
        val supportedMediaCommands = MutableStateFlow<Set<Int>?>(null)
        val phoneTools = MutableStateFlow(false)
        val phoneSummary = MutableStateFlow("")
        val sessionStats = MutableStateFlow(SessionStats())
        private val _nowPlaying = MutableStateFlow(NowPlaying())
        val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()
    }

    data class SessionStats(val received: Int = 0, val posted: Int = 0, val timeouts: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var connectionManager: BleConnectionManager
    private lateinit var scanner: BleScanner
    private lateinit var notificationPreferences: NotificationPreferences
    private lateinit var notificationManager: NotificationManager
    private lateinit var vibrator: Vibrator

    private val dataSourceAssembler = DataSourceAssembler()
    private val appNameCache = mutableMapOf<String, String>()

    // Track active notification IDs in posting order (oldest first) for auto-cleanup
    private val activeNotificationIds = ArrayDeque<Int>()

    private var activeCallUid: Long = -1

    // UIDs that belong to the current ANCS session: only these may send actions to iOS.
    // Alerts from an earlier session stay visible (like Apple Watch) but become read-only.
    private val actionableUids = HashSet<Long>()

    // Serial queue for Control Point requests
    private var sessionToken = java.util.UUID.randomUUID().toString()
    private val pending = PendingAlerts()
    private val wakeProcessor = Channel<Unit>(Channel.CONFLATED)
    private var inFlight: AncsEvent? = null
    private var attributeResponse: CompletableDeferred<Unit>? = null

    private fun resetNotificationSession() {
        sessionToken = java.util.UUID.randomUUID().toString()
        sessionStats.value = SessionStats()
        pending.clear()
        inFlight = null
        attributeResponse?.cancel()
        dataSourceAssembler.reset()
        actionableUids.clear()
    }

    override fun onCreate() {
        super.onCreate()
        connectionManager = BleConnectionManager(this)
        scanner = BleScanner(this)
        notificationPreferences = NotificationPreferences(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        // UIDs are scoped to the ANCS session. Remove stale buttons after process death
        // and migrate untagged mirrored notifications from builds before 1.1.
        notificationManager.activeNotifications.filter {
            it.tag == MIRROR_TAG || (it.tag == null && it.id >= 1000)
        }.forEach { notificationManager.cancel(it.tag, it.id) }
        vibrator = getSystemService(Vibrator::class.java)

        // Register bond state receiver
        registerReceiver(connectionManager.bondStateReceiver, BondStateReceiver.intentFilter)
        // Reset on Bluetooth off, reconnect as soon as it comes back on
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // Register PhoneAccount for Telecom framework integration
        // This lets us show incoming calls via the system call UI
        AncsConnectionService.registerPhoneAccount(this)

        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification("Starting..."))

        connectionManager.onNotificationEvent = ::handleNotificationSourceEvent
        connectionManager.onAttributeData = ::handleDataSourceEvent
        connectionManager.onSessionReset = ::resetNotificationSession

        // Listen for companion app call state events (Live Call Status feature)
        scope.launch {
            connectionManager.callStateEvents.collect { data ->
                handleCompanionCallState(data)
            }
        }

        scope.launch { connectionManager.phoneTools.collect { phoneTools.value = it } }
        scope.launch { connectionManager.phoneSummary.collect { phoneSummary.value = it } }
        scope.launch { connectionManager.supportedCommands.collect { supportedMediaCommands.value = it } }

        scope.launch {
            connectionManager.mediaAvailable.collect { _mediaAvailable.value = it }
        }

        scope.launch {
            connectionManager.nowPlaying.collect { _nowPlaying.value = it }
        }

        // Update service notification and shared state based on connection state
        scope.launch {
            connectionManager.connectionState.collectLatest { state ->
                // Publish to shared flow so ViewModel can observe
                _sharedConnectionState.value = state

                val text = when (state) {
                    is ConnectionState.Idle -> "Idle"
                    is ConnectionState.Scanning -> "Scanning..."
                    is ConnectionState.Connecting -> "Connecting to ${state.deviceName ?: "iPhone"}..."
                    is ConnectionState.Bonding -> "Pairing with ${state.deviceName ?: "iPhone"}..."
                    is ConnectionState.Connected -> "Connected to ${state.deviceName ?: "iPhone"}"
                    is ConnectionState.Disconnected -> "Disconnected"
                    is ConnectionState.Error -> "Error: ${state.message}"
                }
                // The link dropped while a call was ringing: its REMOVED will never come.
                if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting &&
                    activeCallUid != -1L
                ) {
                    Log.i(TAG, "Link lost during a call; dismissing the call screen")
                    endActiveCall()
                }
                notificationManager.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(text))
            }
        }

        // Start the attribute request processor
        startRequestProcessor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                // Try to reconnect to bonded iPhone
                tryReconnectBonded()
            }
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address != null) {
                    val device = scanner.let {
                        val manager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                        manager.adapter.getRemoteDevice(address)
                    }
                    connectionManager.connect(device)
                }
            }
            ACTION_DISCONNECT -> {
                connectionManager.disconnect()
            }
            ACTION_RECONNECT -> {
                tryReconnectBonded()
            }
            ACTION_PERFORM_NOTIFICATION_ACTION -> {
                val uid = intent.getLongExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, -1)
                val actionId = intent.getIntExtra(EXTRA_ACTION_ID, -1)
                if (uid != -1L && actionId != -1) {
                    performNotificationAction(uid, actionId)
                }
            }
            ACTION_SEND_QUICK_REPLY -> {
                val replyText = intent.getStringExtra(EXTRA_QUICK_REPLY_TEXT) ?: return START_STICKY
                val callerName = intent.getStringExtra("caller_name") ?: "Unknown"
                val payload = "$callerName|$replyText"
                Log.d(TAG, "Sending quick reply via BLE")
                scope.launch {
                    if (!connectionManager.writeQuickReply(payload)) {
                        android.widget.Toast.makeText(this@AncsService,
                            "Open WearBridge on iPhone to send replies", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ACTION_MEDIA_COMMAND -> {
                val code = intent.getIntExtra(EXTRA_MEDIA_COMMAND, -1)
                val command = MediaCommand.entries.firstOrNull { it.code == code }
                if (command != null) {
                    scope.launch {
                        if (!connectionManager.sendMediaCommand(command.code.toByte())) {
                            android.widget.Toast.makeText(
                                this@AncsService, "Media control unavailable", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            "swiped_alert" -> {
                val uid = intent.getLongExtra("uid", -1)
                activeNotificationIds.remove(uid.toInt())
                if (intent.getStringExtra("session") == sessionToken && actionableUids.remove(uid)) {
                    val dismissAction = intent.getIntExtra("dismissAction", -1)
                    if (notificationPreferences.syncDismissals && dismissAction in 0..1) {
                        val bytes = ControlPointWriter.buildPerformNotificationAction(uid, dismissAction)
                        scope.launch { connectionManager.writeAttributeRequest(bytes) }
                    }
                }
            }
            "phone_control" -> {
                val code = intent.getIntExtra("code", -1)
                scope.launch {
                    val ok = if (code == 0) connectionManager.refreshPhone() else connectionManager.phoneCommand(code)
                    if (!ok) android.widget.Toast.makeText(this@AncsService,
                        "Unavailable. Check iPhone settings or try again.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_DRAFT -> {
                val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence("body")?.toString()?.trim()
                val uid = intent.getLongExtra("uid", -1)
                if (!text.isNullOrEmpty() && uid in actionableUids && intent.getStringExtra("session") == sessionToken) {
                    val payload = org.json.JSONObject().put("id", java.util.UUID.randomUUID().toString())
                        .put("sender", intent.getStringExtra("sender").orEmpty()).put("body", text)
                        .toString().encodeToByteArray()
                    scope.launch {
                        val saved = connectionManager.saveDraft(payload)
                        val message = if (saved) "Draft saved. Open Drafts on iPhone to send." else "Draft unconfirmed. Check iPhone Drafts, connection and text length."
                        android.widget.Toast.makeText(this@AncsService, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            ACTION_CLEAR_ALL -> {
                Log.i(TAG, "Clearing all notifications (${activeNotificationIds.size} tracked)")
                activeNotificationIds.forEach { id ->
                    notificationManager.cancel(MIRROR_TAG, id)
                }
                activeNotificationIds.clear()
                actionableUids.clear()
                pending.clear()
                inFlight = null
                attributeResponse?.complete(Unit)
            }
            ACTION_STOP -> {
                connectionManager.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(connectionManager.bondStateReceiver)
        } catch (_: IllegalArgumentException) { }
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) { }
        connectionManager.destroy()
        _mediaAvailable.value = false
        _nowPlaying.value = NowPlaying()
        wakeProcessor.close()
        _sharedConnectionState.value = ConnectionState.Idle
        scope.cancel()
    }

    /**
     * A Bluetooth restart (toggle, stack crash, OTA, some OEM co-processor handovers) kills
     * every GATT client without an onConnectionStateChange callback, so the service stayed
     * in Connecting/Connected with a dead BluetoothGatt. Reset on OFF, reconnect on ON.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.w(TAG, "Bluetooth going off: resetting connection")
                    connectionManager.onBluetoothOff()
                }
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth on: reconnecting")
                    connectionManager.onBluetoothOn()
                }
            }
        }
    }

    /** Dismisses the call screen, Telecom call and call notification. */
    private fun endActiveCall() {
        activeCallUid = -1
        vibrator.cancel()
        notificationManager.cancel(NOTIFICATION_ID_CALL)
        AncsConnectionService.endActiveCall()
        sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ENDED).apply {
            setPackage(packageName)
        })
    }

    private fun tryReconnectBonded() {
        val device = connectionManager.getBondedIPhone()
        if (device != null) {
            Log.i(TAG, "Found bonded device: ${device.name ?: device.address}")
            connectionManager.reconnect(device)
        } else {
            Log.i(TAG, "No bonded iPhone found")
        }
    }

    private fun handleNotificationSourceEvent(data: ByteArray) {
        val event = NotificationSourceParser.parse(data) ?: run {
            Log.w(TAG, "Failed to parse NS event")
            return
        }

        Log.d(TAG, "NS event: id=${event.eventId} cat=${event.categoryId} uid=${event.notificationUid}")

        sessionStats.value = sessionStats.value.copy(received = sessionStats.value.received + 1)
        when {
            event.isAdded || event.isModified -> {
                // Ignore historical calls and honor the catch-up preference.
                if (event.isPreExisting && (event.isIncomingCall || !notificationPreferences.catchUp)) {
                    Log.d(TAG, "Skipping pre-existing NS event uid=${event.notificationUid}")
                    return
                }

                // MODIFIED on active call = call was answered on iPhone.
                // Transition the call overlay to "Active on iPhone" state.
                if (event.isModified && event.isIncomingCall && activeCallUid == event.notificationUid) {
                    Log.i(TAG, "Call MODIFIED (answered on iPhone) uid=${event.notificationUid}")
                    vibrator.cancel()
                    // Cancel the CallStyle notification — no longer ringing
                    notificationManager.cancel(NOTIFICATION_ID_CALL)
                    // Tell the activity to transition to "active call" UI
                    sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ANSWERED).apply {
                        setPackage(packageName)
                    })
                    return
                }

                if (event.isIncomingCall) {
                    // IMMEDIATELY show call screen — don't wait for attribute fetch.
                    // The NS payload already tells us it's a call. We'll update
                    // the caller name later if attributes arrive in time.
                    Log.i(TAG, "Incoming call NS event (cat=1) uid=${event.notificationUid} — showing call screen NOW")
                    showIncomingCall(AncsNotification(
                        uid = event.notificationUid,
                        categoryId = event.categoryId,
                        eventFlags = event.eventFlags,
                        appIdentifier = "com.apple.mobilephone",
                        title = "Incoming Call",
                        subtitle = null,
                        message = "",
                        date = null,
                        appDisplayName = "Phone",
                        positiveActionLabel = "Answer",
                        negativeActionLabel = "Decline"
                    ))
                }

                pending.offer(event)
                wakeProcessor.trySend(Unit)
            }
            event.isRemoved -> {
                pending.remove(event.notificationUid)
                if (inFlight?.notificationUid == event.notificationUid) {
                    inFlight = null
                    attributeResponse?.complete(Unit)
                }
                // Cancel the Android notification and remove from tracking
                val removedId = notificationIdForUid(event.notificationUid)
                notificationManager.cancel(MIRROR_TAG, removedId)
                activeNotificationIds.remove(removedId)
                actionableUids.remove(event.notificationUid)

                // If this was the call on screen, dismiss everything (not for another
                // call ending while a second one rings: call waiting).
                if (activeCallUid == event.notificationUid) {
                    Log.i(TAG, "Call ended (REMOVE) uid=${event.notificationUid}")
                    endActiveCall()
                }
            }
        }
    }

    /**
     * Handle call state pushed from the iOS companion app via BLE.
     * This is the "Live Call Status" feature — more reliable than ANCS MODIFIED events.
     * Values: 0x00=idle, 0x01=ringing, 0x02=active (answered), 0x03=ended
     */
    private fun handleCompanionCallState(data: ByteArray) {
        if (data.isEmpty()) return
        val state = data[0]
        Log.i(TAG, "Companion call state: ${when (state) {
            AncsConstants.CALL_STATE_RINGING -> "ringing"
            AncsConstants.CALL_STATE_ACTIVE -> "active"
            AncsConstants.CALL_STATE_ENDED -> "ended"
            else -> "idle"
        }}")

        when (state) {
            AncsConstants.CALL_STATE_ACTIVE -> {
                // Call was answered on iPhone — transition overlay
                if (activeCallUid != -1L) {
                    Log.i(TAG, "Companion: call answered on iPhone, transitioning overlay")
                    vibrator.cancel()
                    notificationManager.cancel(NOTIFICATION_ID_CALL)
                    sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ANSWERED).apply {
                        setPackage(packageName)
                    })
                }
            }
            AncsConstants.CALL_STATE_ENDED -> {
                // Call ended on iPhone — dismiss everything
                if (activeCallUid != -1L) {
                    Log.i(TAG, "Companion: call ended on iPhone, dismissing overlay")
                    activeCallUid = -1
                    vibrator.cancel()
                    notificationManager.cancel(NOTIFICATION_ID_CALL)
                    AncsConnectionService.endActiveCall()
                    sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ENDED).apply {
                        setPackage(packageName)
                    })
                }
            }
        }
    }

    private fun handleDataSourceEvent(data: ByteArray) {
        val event = inFlight ?: return
        val response = attributeResponse ?: return
        val notification = dataSourceAssembler.onDataReceived(data)
        if (notification != null) {
            response.complete(Unit)
            Log.d(TAG, "Notification attributes received for uid=${notification.uid}")
            if (!pending.contains(event.notificationUid)) postNotification(notification)
        }
    }

    private fun startRequestProcessor() {
        scope.launch {
            for (signal in wakeProcessor) {
                while (true) {
                    val event = pending.next() ?: break
                    processAttributeRequest(event)
                }
            }
        }
    }

    private suspend fun processAttributeRequest(event: AncsEvent) {
        inFlight = event
        val attrs = mutableListOf(AncsConstants.ATTR_APP_IDENTIFIER, AncsConstants.ATTR_TITLE,
            AncsConstants.ATTR_SUBTITLE, AncsConstants.ATTR_MESSAGE, AncsConstants.ATTR_DATE)
        if (event.hasPositiveAction) attrs.add(AncsConstants.ATTR_POSITIVE_ACTION_LABEL)
        if (event.hasNegativeAction) attrs.add(AncsConstants.ATTR_NEGATIVE_ACTION_LABEL)
        // Prepare the assembler to expect this response
        dataSourceAssembler.expectNotificationAttributes(
            uid = event.notificationUid,
            requestedAttributes = attrs,
            category = event.categoryId,
            flags = event.eventFlags
        )

        // Write the Control Point request
        val cpData = ControlPointWriter.buildGetNotificationAttributes(
            uid = event.notificationUid,
            hasPositiveAction = event.hasPositiveAction,
            hasNegativeAction = event.hasNegativeAction
        )

        val response = CompletableDeferred<Unit>()
        attributeResponse = response
        try {
            val accepted = connectionManager.writeAttributeRequest(cpData)
            if (!accepted) {
                Log.w(TAG, "Could not request attributes for uid=${event.notificationUid}")
                return
            }
            // Finishing early avoids delaying a burst of messages. Timeout caps the
            // wait if iOS removed the notification before answering.
            if (withTimeoutOrNull(2_000) { response.join() } == null) {
                sessionStats.value = sessionStats.value.copy(timeouts = sessionStats.value.timeouts + 1)
                Log.w(TAG, "Attribute response timed out for uid=${event.notificationUid}")
            }
        } finally {
            inFlight = null
            if (attributeResponse === response) attributeResponse = null
            dataSourceAssembler.reset()
        }
    }

    private fun postNotification(notification: AncsNotification) {
        val isPreExisting = notification.eventFlags and AncsConstants.EVENT_FLAG_PRE_EXISTING != 0

        Log.d(TAG, "postNotification: uid=${notification.uid} cat=${notification.categoryId} " +
            "flags=0x${notification.eventFlags.toString(16)} app=${notification.appIdentifier} " +
            "preExisting=$isPreExisting")

        // Resolve app display name if not cached
        val appName = resolveAppName(notification.appIdentifier)
        val updatedNotification = notification.copy(appDisplayName = appName)

        // Detect incoming calls by category OR by message content
        // WhatsApp sends calls as cat=4 (Social) with "☎" or "Incoming" in message
        // Regular phone may send as cat=1 (IncomingCall) or cat=2 with "Incoming" content
        val callApps = setOf(
            "net.whatsapp.WhatsApp", "net.whatsapp.WhatsAppSMB",
            "com.apple.mobilephone", "com.apple.facetime"
        )
        val isIncomingCall = CallDetection.isIncomingCall(
            categoryId = notification.categoryId,
            appIdentifier = notification.appIdentifier,
            title = notification.title,
            message = notification.message,
            hasPositiveAction = notification.positiveActionLabel != null,
            callApps = callApps
        )

        if (isIncomingCall) {
            if (isPreExisting) return
            Log.i(TAG, "Showing incoming call screen")
            showIncomingCall(updatedNotification)
            return
        }

        notificationPreferences.rememberApp(
            notification.appIdentifier, appName ?: notification.appIdentifier
        )
        if (!notificationPreferences.isEnabled(notification.appIdentifier)) return

        // Route alarm/timer apps to the schedule channel regardless of ANCS category
        val alarmApps = setOf("com.apple.mobiletimer", "com.apple.reminders", "com.apple.mobilecal")
        val quiet = isPreExisting || notification.eventFlags and AncsConstants.EVENT_FLAG_SILENT != 0
        val channelId = if (quiet) {
            AncsApplication.CHANNEL_QUIET
        } else if (notification.appIdentifier.equals("com.apple.MobileSMS", ignoreCase = true)) {
            AncsApplication.CHANNEL_MESSAGES
        } else if (notification.appIdentifier in alarmApps) {
            AncsApplication.CHANNEL_SCHEDULE
        } else {
            AncsApplication.channelForCategory(notification.categoryId)
        }
        val iconResId = AppIconMapper.getIconResId(notification.appIdentifier, notification.categoryId)
        Log.d(TAG, "Icon mapping: app='${notification.appIdentifier}' cat=${notification.categoryId} -> resId=$iconResId (default=${R.drawable.ic_notification_default})")
        val notifId = notificationIdForUid(notification.uid)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Layout: contentTitle = sender, contentText = message preview, subText = app name
        val showPreviews = notificationPreferences.showPreviews
        val senderName = if (showPreviews && notification.title.isNotEmpty()) {
            notification.title
        } else {
            appName ?: "iPhone"
        }
        val messagePreview = if (showPreviews) {
            notification.message.ifEmpty { notification.subtitle.orEmpty() }
        } else {
            "New notification"
        }
        val notifTime = parseAncsDate(notification.date)

        val badge = AppIconMapper.badge(this, notification.appIdentifier, notification.categoryId)
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(iconResId)
            .setLargeIcon(badge)
            .setColor(AppIconMapper.colorFor(notification.appIdentifier, notification.categoryId))
            .setContentTitle(senderName)
            .setContentText(messagePreview)
            .setSubText(appName ?: notification.appIdentifier)
            .setTicker("$senderName: $messagePreview")
            .setWhen(notifTime)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidCategory(notification.categoryId))
            // Each notification gets a unique group key to prevent Android's auto-grouping.
            // Auto-grouping adds SILENT flag via GROUP_ALERT_SUMMARY which kills
            // heads-up overlays, vibration, and sound on ALL child notifications.
            .setGroup("ancs_${notification.uid}")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)

        val delete = Intent(this, AncsService::class.java).setAction("swiped_alert")
            .putExtra("uid", notification.uid)
            .putExtra("session", sessionToken)
            .putExtra("dismissAction", notification.dismissAction() ?: -1)
        builder.setDeleteIntent(PendingIntent.getService(this, notifId, delete,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

        // MessagingStyle — Samsung One UI Watch renders this with message body
        // in the heads-up overlay, matching native Android-paired notification style.
        // BigTextStyle only shows body in expanded view, not the overlay.
        if (messagePreview.isNotEmpty()) {
            val person = androidx.core.app.Person.Builder()
                .setName(senderName)
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(badge))
                .build()
            builder.setStyle(
                NotificationCompat.MessagingStyle(person)
                    .addMessage(messagePreview, notifTime, person)
            )

        }

        if (showPreviews && connectionManager.canDraft && notification.appIdentifier.equals("com.apple.MobileSMS", true)) {
            val draftIntent = Intent(this, AncsService::class.java).apply {
                action = ACTION_DRAFT
                putExtra("uid", notification.uid)
                putExtra("sender", notification.title)
                putExtra("session", sessionToken)
            }
            val input = androidx.core.app.RemoteInput.Builder("body").setLabel("Draft for iPhone").build()
            val target = PendingIntent.getService(this, notifId, draftIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(R.drawable.ic_chat_reply, "Draft reply", target)
                .addRemoteInput(input).build())
        }

        // Add action buttons if available
        if (notification.positiveActionLabel != null) {
            val positiveIntent = PendingIntent.getBroadcast(
                this, notifId,
                Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_POSITIVE).apply {
                    setPackage(packageName)
                    putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, notification.uid)
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_check, notification.positiveActionLabel, positiveIntent)
        }

        if (notification.negativeActionLabel != null) {
            val negativeIntent = PendingIntent.getBroadcast(
                this, notifId + 500_000,
                Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_NEGATIVE).apply {
                    setPackage(packageName)
                    putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, notification.uid)
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_close, notification.negativeActionLabel, negativeIntent)
        }

        if (quiet) builder.setSilent(true)

        // Auto-cleanup: evict oldest notifications if approaching the system cap
        // A MODIFIED event updates the same notification without evicting another.
        activeNotificationIds.remove(notifId)
        if (isPreExisting && activeNotificationIds.size >= MAX_ACTIVE_NOTIFICATIONS) return
        evictOldestIfNeeded()
        notificationManager.notify(MIRROR_TAG, notifId, builder.build())
        sessionStats.value = sessionStats.value.copy(posted = sessionStats.value.posted + 1)
        if (isPreExisting) activeNotificationIds.addFirst(notifId) else activeNotificationIds.addLast(notifId)
        actionableUids.add(notification.uid)
    }

    /**
     * Evict the oldest notifications to stay under the system limit.
     * Keeps MAX_ACTIVE_NOTIFICATIONS slots, leaving room for service + call notifications.
     */
    private fun evictOldestIfNeeded() {
        while (activeNotificationIds.size >= MAX_ACTIVE_NOTIFICATIONS) {
            val oldId = activeNotificationIds.removeFirst()
            notificationManager.cancel(MIRROR_TAG, oldId)
            Log.d(TAG, "Auto-evicted oldest notification id=$oldId (${activeNotificationIds.size} active)")
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun showIncomingCall(notification: AncsNotification) {
        val callerName = notification.title.ifEmpty { "Incoming Call" }
        val appName = notification.appDisplayName ?: "Phone"

        // Track active call UID to avoid duplicate showIncomingCall calls
        if (activeCallUid == notification.uid) {
            // Already showing — just update caller name if it changed
            if (callerName != "Incoming Call") {
                AncsConnectionService.activeConnection?.updateCallerName(callerName)
                // Also update the IncomingCallActivity via broadcast
                sendBroadcast(Intent(IncomingCallActivity.ACTION_CALLER_NAME_UPDATED).apply {
                    setPackage(packageName)
                    putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
                })
                Log.d(TAG, "Updated caller info for uid=${notification.uid}")
            }
            return
        }
        activeCallUid = notification.uid

        // 1. Report to Telecom framework — self-managed connection allows
        //    our foreground service to launch activities while ringing
        AncsConnectionService.reportIncomingCall(
            this, notification.uid, callerName, appName
        )

        // 2. Directly launch call activity — the Telecom self-managed connection
        //    grants us the background activity start exception
        val callIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(IncomingCallActivity.EXTRA_NOTIFICATION_UID, notification.uid)
            putExtra(IncomingCallActivity.EXTRA_APP_NAME, appName)
        }
        try {
            startActivity(callIntent)
            Log.i(TAG, "Started incoming call activity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start IncomingCallActivity: ${e.message}")
        }

        // 3. Post CallStyle notification as fallback
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answerPendingIntent = PendingIntent.getActivity(
            this, 1, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePendingIntent = PendingIntent.getBroadcast(
            this, notification.uid.toInt(),
            Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_NEGATIVE).apply {
                setPackage(packageName)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, notification.uid)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val caller = androidx.core.app.Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()
        val callNotification = NotificationCompat.Builder(this, AncsApplication.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller, declinePendingIntent, answerPendingIntent
                )
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID_CALL, callNotification)
        Log.i(TAG, "Posted CallStyle notification")
    }


    private fun performNotificationAction(uid: Long, actionId: Int) {
        if (uid !in actionableUids && uid != activeCallUid) return
        val data = ControlPointWriter.buildPerformNotificationAction(uid, actionId)
        scope.launch {
            if (!connectionManager.writeAttributeRequest(data)) {
                android.widget.Toast.makeText(this@AncsService, "iPhone unavailable", android.widget.Toast.LENGTH_SHORT).show()
            }
            // iOS will send MODIFIED or REMOVED if this action changes the alert.
        }
    }

    private fun resolveAppName(appIdentifier: String): String? {
        if (appIdentifier.isEmpty()) return null
        appNameCache[appIdentifier]?.let { return it }

        // For now, derive a readable name from the bundle ID
        // A full implementation would use GetAppAttributes, but that requires
        // another CP/DS round trip. We use a simple mapping instead.
        val name = knownAppNamesLower[appIdentifier.lowercase()] ?: run {
            // Extract last component: "com.apple.MobileSMS" -> "MobileSMS"
            appIdentifier.substringAfterLast(".")
        }
        appNameCache[appIdentifier] = name
        return name
    }

    private fun buildNotificationText(notification: AncsNotification): String {
        return buildString {
            if (notification.title.isNotEmpty()) {
                append(notification.title)
            }
            if (!notification.subtitle.isNullOrEmpty()) {
                if (isNotEmpty()) append(": ")
                append(notification.subtitle)
            }
            if (notification.message.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(notification.message)
            }
        }
    }

    private fun parseAncsDate(date: String?): Long {
        if (date == null) return System.currentTimeMillis()
        return try {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
            sdf.parse(date)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun androidCategory(categoryId: Int): String = when (categoryId) {
        AncsConstants.CATEGORY_INCOMING_CALL -> Notification.CATEGORY_CALL
        AncsConstants.CATEGORY_MISSED_CALL -> Notification.CATEGORY_MISSED_CALL
        AncsConstants.CATEGORY_VOICEMAIL -> Notification.CATEGORY_VOICEMAIL
        AncsConstants.CATEGORY_SOCIAL -> Notification.CATEGORY_SOCIAL
        AncsConstants.CATEGORY_SCHEDULE -> Notification.CATEGORY_EVENT
        AncsConstants.CATEGORY_EMAIL -> Notification.CATEGORY_EMAIL
        else -> Notification.CATEGORY_MESSAGE
    }

    private fun notificationIdForUid(uid: Long): Int =
        uid.toInt()

    private fun buildServiceNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AncsApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification_default)
            .setContentTitle("WearBridge")
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private val knownAppNamesLower by lazy { knownAppNames.mapKeys { it.key.lowercase() } }

    private val knownAppNames = mapOf(
        "vn.com.vng.zingalo" to "Zalo",
        // Apple Apps
        "com.apple.MobileSMS" to "Messages",
        "com.apple.mobilephone" to "Phone",
        "com.apple.mobilemail" to "Mail",
        "com.apple.mobilecal" to "Calendar",
        "com.apple.facetime" to "FaceTime",
        "com.apple.reminders" to "Reminders",
        "com.apple.news" to "News",
        "com.apple.mobilenotes" to "Notes",
        "com.apple.Health" to "Health",
        "com.apple.Fitness" to "Fitness",
        "com.apple.weather" to "Weather",
        "com.apple.Maps" to "Maps",
        "com.apple.Music" to "Music",
        "com.apple.stocks" to "Stocks",
        "com.apple.camera" to "Camera",
        "com.apple.Passbook" to "Wallet",
        "com.apple.Preferences" to "Settings",
        "com.apple.mobiletimer" to "Clock",
        "com.apple.podcasts" to "Podcasts",
        "com.apple.findmy" to "Find My",
        "com.apple.Photos" to "Photos",
        "com.apple.TestFlight" to "TestFlight",
        "com.apple.AppStore" to "App Store",
        "com.apple.Passwords" to "Passwords",
        "com.apple.tips" to "Tips",
        "com.apple.shortcuts" to "Shortcuts",
        "com.apple.Home" to "Home",
        "com.apple.iBooks" to "Books",
        "com.apple.tv" to "TV",
        "com.apple.ScreenTimeNotifications" to "Screen Time",

        // Messaging
        "net.whatsapp.WhatsApp" to "WhatsApp",
        "net.whatsapp.WhatsAppSMB" to "WhatsApp Business",
        "org.telegram.Telegram" to "Telegram",
        "com.facebook.Messenger" to "Messenger",
        "org.whispersystems.signal" to "Signal",
        "com.slack.Slack" to "Slack",
        "com.hammerandchisel.discord" to "Discord",

        // Social
        "com.atebits.Tweetie2" to "X",
        "com.burbn.instagram" to "Instagram",
        "com.facebook.Facebook" to "Facebook",
        "com.linkedin.LinkedIn" to "LinkedIn",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.toyopagroup.picaboo" to "Snapchat",
        "com.burbn.barcelona" to "Threads",
        "com.reddit.Reddit" to "Reddit",

        // Email & Productivity
        "com.google.Gmail" to "Gmail",
        "com.microsoft.Office.Outlook" to "Outlook",
        "com.google.calendar" to "Google Calendar",
        "notion.id" to "Notion",
        "com.grammarly.keyboard" to "Grammarly",

        // Google Apps
        "com.google.GoogleMobile" to "Google",
        "com.google.Maps" to "Google Maps",
        "com.google.Drive" to "Google Drive",
        "com.google.Photos" to "Google Photos",
        "com.google.photos" to "Google Photos",
        "com.google.Home" to "Google Home",
        "com.google.chrome.ios" to "Chrome",
        "com.google.ios.youtube" to "YouTube",

        // Entertainment & Media
        "com.spotify.client" to "Spotify",
        "com.shazam.Shazam" to "Shazam",

        // Ride-sharing & Delivery
        "com.ubercab.UberClient" to "Uber",
        "com.ubercab.UberEats" to "Uber Eats",

        // Finance & Banking
        "au.com.westpac.ConsultWPC" to "Westpac",
        "au.com.westpac.banking" to "Westpac",
        "com.stake.stake" to "Stake",
        "au.com.hellostake" to "Stake",
        "com.afterpay.afterpay-consumer" to "Afterpay",

        // Health & Fitness
        "com.anytimefitness.club" to "Anytime Fitness",
        "com.anytimefitness.atfmobile" to "Anytime Fitness",
        "au.com.hotdoc.app" to "HotDoc",
        "com.hotdoc.patient" to "HotDoc",

        // Telecom
        "com.jio.myjio" to "Jio",
        "com.ril.ajio" to "Jio",

        // Shopping & Services
        "au.com.auspost" to "AusPost",
        "com.auspost.MyPost" to "AusPost",

        // Smart Home
        "com.philips.hue.gen4" to "Hue",
        "com.signify.hue.blue" to "Hue",

        // Gaming
        "com.microsoft.smartglass" to "Xbox",
        "com.microsoft.xboxapp" to "Xbox",

        // VPN
        "com.surfshark.vpnclient" to "Surfshark",

        // Other
        "com.producthunt.ProductHuntApp" to "Product Hunt",
        "com.github.stormcrow" to "GitHub",
    )
}
