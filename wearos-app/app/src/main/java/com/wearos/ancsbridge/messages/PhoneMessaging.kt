package com.wearos.ancsbridge.messages

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Messages and contacts on the iPhone over Bluetooth Classic, the same way car kits do:
 * - MAP (Message Access Profile, iOS supports MAP 1.4): list inbox/sent, push new messages.
 *   iOS sends them from the Messages app (iMessage or SMS).
 * - PBAP (Phone Book Access Profile): contacts with phone numbers and emails.
 *
 * Needs a Bluetooth Classic bond with the iPhone (separate from the BLE/ANCS bond) and, on
 * the iPhone, "Show Notifications" and "Sync Contacts" turned on for this watch in
 * Settings › Bluetooth › ⓘ.
 */
@SuppressLint("MissingPermission")
object PhoneMessaging {
    private const val TAG = "PhoneMessaging"
    private const val PREFS = "messaging"

    private val MAS_UUID: UUID = UUID.fromString("00001132-0000-1000-8000-00805f9b34fb")
    private val PSE_UUID: UUID = UUID.fromString("0000112f-0000-1000-8000-00805f9b34fb")
    private val MAS_TARGET = uuidBytes("bb582b40-420c-11db-b0de-0800200c9a66")
    private val PBAP_TARGET = uuidBytes("796135f0-f0c5-11d8-0966-0800200c9a66")

    // MAP application parameter tags
    private const val MAP_MAX_LIST_COUNT = 0x01
    private const val MAP_SUBJECT_LENGTH = 0x13
    private const val MAP_PARAMETER_MASK = 0x10
    private const val MAP_ATTACHMENT = 0x0A
    private const val MAP_CHARSET = 0x14
    private const val MAP_TRANSPARENT = 0x0B
    private const val MAP_RETRY = 0x0C
    private const val CHARSET_UTF8 = 1
    /** subject, datetime, sender name/address, recipient name/address, type, read. */
    private const val LISTING_MASK = 0x0000107FL

    // PBAP application parameter tags
    private const val PBAP_FORMAT = 0x07
    private const val PBAP_MAX_LIST_COUNT = 0x04
    private const val PBAP_PROPERTY_SELECTOR = 0x06
    /** VERSION, FN, N, TEL, EMAIL. */
    private const val PBAP_PROPERTIES = 0x18FL

    sealed interface Status {
        data object NotSetUp : Status
        data object Ready : Status
        data object Working : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.NotSetUp)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val lock = Mutex()
    private var loaded = false

    // ---- Setup ------------------------------------------------------------------------

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun device(context: Context): BluetoothDevice? {
        val address = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("device", null) ?: return null
        return adapter(context)?.bondedDevices?.firstOrNull { it.address == address }
    }

    fun isSetUp(context: Context) = device(context) != null

    /** Classic-bonded phones the user can pick (the LE-only ANCS bond does not count). */
    fun bondedPhones(context: Context): List<BluetoothDevice> =
        adapter(context)?.bondedDevices.orEmpty().filter {
            it.type != BluetoothDevice.DEVICE_TYPE_LE &&
                (it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE ||
                    it.name.orEmpty().contains("iPhone", ignoreCase = true))
        }

    fun choose(context: Context, device: BluetoothDevice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("device", device.address).apply()
        _status.value = Status.Ready
    }

    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _conversations.value = emptyList()
        _contacts.value = emptyList()
        _status.value = Status.NotSetUp
    }

    /** Loads cached conversations/contacts so the UI has something before the first sync. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _contacts.value = prefs.getString("contacts", null)?.let(::decodeContacts).orEmpty()
        val messages = prefs.getString("messages", null)?.let(::decodeMessages).orEmpty()
        _conversations.value = MapFormats.conversations(messages, _contacts.value)
        _status.value = if (isSetUp(context)) Status.Ready else Status.NotSetUp
    }

    // ---- Sessions -----------------------------------------------------------------------

    private fun open(context: Context, uuid: UUID): BluetoothSocket {
        val device = device(context) ?: throw IllegalStateException("Messages are not set up")
        adapter(context)?.cancelDiscovery()
        val socket = device.createRfcommSocketToServiceRecord(uuid)
        try {
            socket.connect()
        } catch (e: java.io.IOException) {
            runCatching { socket.close() }
            throw java.io.IOException(
                if (uuid == MAS_UUID) "Can't reach iPhone messages over Bluetooth. Keep the iPhone nearby and unlocked."
                else "Can't reach iPhone contacts over Bluetooth.", e
            )
        }
        return socket
    }

    private suspend fun <T> session(
        context: Context,
        uuid: UUID,
        target: ByteArray,
        block: (ObexClient) -> T
    ): T = withContext(Dispatchers.IO) {
        lock.withLock {
            // Like car kits, announce MAP features (MapSupportedFeatures, tag 0x29).
            val features = if (uuid == MAS_UUID) AppParams().int(0x29, 0x0000007FL).build() else null
            var attempt = 0
            while (true) {
                attempt++
                val socket = open(context, uuid)
                try {
                    val obex = ObexClient(socket.inputStream, socket.outputStream)
                    if (!obex.connect(target, features)) {
                        // iOS may refuse while it asks on the iPhone screen, or just after
                        // Show Notifications was turned on: try once more on a new link.
                        if (attempt == 1 && obex.lastConnectCode == 0xC3) {
                            runCatching { socket.close() }
                            Thread.sleep(2_000)
                            continue
                        }
                        val code = ObexClient.describe(obex.lastConnectCode)
                        error(
                            if (uuid == MAS_UUID) "iPhone refused messages ($code). Unlock the iPhone and check Bluetooth › ⓘ › Show Notifications " +
                                "(not Share System Notifications), then Refresh. Still refused? Messages › Set Up again."
                            else "iPhone refused contacts ($code). On iPhone: Bluetooth › ⓘ › Sync Contacts."
                        )
                    }
                    return@withLock try { block(obex) } finally { obex.disconnect() }
                } finally {
                    runCatching { socket.close() }
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private suspend fun <T> mas(context: Context, block: (ObexClient) -> T): T =
        session(context, MAS_UUID, MAS_TARGET) { obex ->
            check(obex.setPath(null) && obex.setPath("telecom") && obex.setPath("msg")) { "Message folders unavailable" }
            block(obex)
        }

    private inline fun <T> tracked(action: () -> T): T? = try {
        val value = action()
        _status.value = Status.Ready
        value
    } catch (e: Exception) {
        Log.w(TAG, "Messaging failed", e)
        _status.value = Status.Error(e.message ?: "iPhone not reachable over Bluetooth")
        null
    }

    private fun listing(obex: ObexClient, folder: String, outgoing: Boolean): List<MapMessage> {
        val params = AppParams()
            .short(MAP_MAX_LIST_COUNT, 40)
            .byte(MAP_SUBJECT_LENGTH, 255)
            .int(MAP_PARAMETER_MASK, LISTING_MASK)
            .build()
        val response = obex.get(folder, "x-bt/MAP-msg-listing", params)
        if (!response.success) return emptyList()
        return MapFormats.parseListing(response.body().toString(Charsets.UTF_8), outgoing)
    }

    /** Re-reads inbox and sent from the iPhone. */
    suspend fun refresh(context: Context): Boolean {
        load(context)
        if (!isSetUp(context)) return false
        _status.value = Status.Working
        val messages = tracked {
            mas(context) { obex -> listing(obex, "inbox", false) + listing(obex, "sent", true) }
        } ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("messages", encodeMessages(messages.sortedByDescending { it.timeMs }.take(120))).apply()
        _conversations.value = MapFormats.conversations(messages, _contacts.value)
        return true
    }

    /** Sends a message through the iPhone's Messages app. */
    suspend fun send(context: Context, address: String, text: String): Boolean {
        if (!isSetUp(context) || address.isBlank() || text.isBlank()) return false
        _status.value = Status.Working
        val ok = tracked {
            mas(context) { obex ->
                val params = AppParams().byte(MAP_CHARSET, CHARSET_UTF8)
                    .byte(MAP_TRANSPARENT, 0).byte(MAP_RETRY, 1).build()
                val body = MapFormats.buildBMessage(address, text).toByteArray(Charsets.UTF_8)
                obex.put("outbox", "x-bt/message", params, body).success
            }
        } == true
        if (ok) appendSent(context, address, text)
        else if (_status.value !is Status.Error) _status.value = Status.Error("iPhone did not accept the message")
        return ok
    }

    /** Replies to a Messages alert: finds the sender's address in the inbox, then sends. */
    suspend fun replyToAlert(context: Context, senderTitle: String, alertText: String, reply: String): Boolean {
        load(context)
        if (!isSetUp(context)) return false
        val inbox = tracked { mas(context) { obex -> listing(obex, "inbox", false) } } ?: return false
        val address = MapFormats.addressForAlert(inbox, senderTitle, alertText)
            ?: _contacts.value.firstOrNull { it.name.equals(senderTitle.trim(), ignoreCase = true) }?.addresses?.firstOrNull()
            ?: return false.also { _status.value = Status.Error("Couldn't find who sent this message") }
        return send(context, address, reply)
    }

    /** Full text of one message (listings carry at most 255 characters). */
    suspend fun fullText(context: Context, handle: String): String? = tracked {
        mas(context) { obex ->
            val params = AppParams().byte(MAP_ATTACHMENT, 0).byte(MAP_CHARSET, CHARSET_UTF8).build()
            val response = obex.get(handle, "x-bt/message", params)
            if (response.success) MapFormats.parseBMessageText(response.body().toString(Charsets.UTF_8)) else null
        }
    }

    /** Pulls the iPhone's contacts (names, numbers, emails). */
    suspend fun syncContacts(context: Context): Boolean {
        load(context)
        if (!isSetUp(context)) return false
        _status.value = Status.Working
        val contacts = tracked {
            session(context, PSE_UUID, PBAP_TARGET) { obex ->
                val params = AppParams()
                    .byte(PBAP_FORMAT, 0) // vCard 2.1
                    .short(PBAP_MAX_LIST_COUNT, 0xFFFF)
                    .long(PBAP_PROPERTY_SELECTOR, PBAP_PROPERTIES)
                    .build()
                val response = obex.get("telecom/pb.vcf", "x-bt/phonebook", params)
                check(response.success) { "iPhone refused contacts: turn on Sync Contacts for this watch" }
                MapFormats.parseVCards(response.body().toString(Charsets.UTF_8))
            }
        } ?: return false
        runCatching { Avatars.store(context, contacts) }.onFailure { Log.w(TAG, "Contact photos failed", it) }
        _contacts.value = contacts.map { it.copy(photo = null) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("contacts", encodeContacts(contacts)).apply()
        _conversations.value = MapFormats.conversations(_conversations.value.flatMap { it.messages }, contacts)
        return true
    }

    private fun appendSent(context: Context, address: String, text: String) {
        val sent = MapMessage("local-${System.currentTimeMillis()}", text, System.currentTimeMillis(),
            "", "", "", address, true, true)
        val all = _conversations.value.flatMap { it.messages } + sent
        _conversations.value = MapFormats.conversations(all, _contacts.value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("messages", encodeMessages(all.sortedByDescending { it.timeMs }.take(120))).apply()
    }

    // ---- Cache encoding ------------------------------------------------------------------

    private fun encodeMessages(list: List<MapMessage>) = JSONArray().apply {
        list.forEach { m ->
            put(JSONObject().put("h", m.handle).put("s", m.subject).put("t", m.timeMs)
                .put("sn", m.senderName).put("sa", m.senderAddress)
                .put("rn", m.recipientName).put("ra", m.recipientAddress)
                .put("r", m.read).put("o", m.outgoing))
        }
    }.toString()

    private fun decodeMessages(json: String): List<MapMessage> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MapMessage(o.getString("h"), o.optString("s"), o.optLong("t"), o.optString("sn"), o.optString("sa"),
                o.optString("rn"), o.optString("ra"), o.optBoolean("r", true), o.optBoolean("o"))
        }
    }.getOrDefault(emptyList())

    private fun encodeContacts(list: List<Contact>) = JSONArray().apply {
        list.forEach { c ->
            put(JSONObject().put("n", c.name).put("p", JSONArray(c.phones)).put("e", JSONArray(c.emails)))
        }
    }.toString()

    private fun decodeContacts(json: String): List<Contact> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            Contact(o.getString("n"), strings("p"), strings("e"))
        }
    }.getOrDefault(emptyList())
}
