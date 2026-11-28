package com.wearos.ancsbridge.messages

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Publishes the client-side MAP/PBAP service records a car kit has, so the iPhone treats
 * the watch as a messaging/contacts device and shows "Show Notifications" and
 * "Sync Contacts" for it:
 * - MNS (Message Notification Server, 0x1133): where iOS pushes new-message events.
 *   A minimal OBEX server accepts them and triggers a refresh.
 * - PCE (Phone Book Client, 0x112E).
 * iOS reads these records when it pairs, so they must exist before Messages › Set Up.
 */
@SuppressLint("MissingPermission")
object MapServerRecords {
    private const val TAG = "MapServerRecords"
    private val MNS_UUID: UUID = UUID.fromString("00001133-0000-1000-8000-00805f9b34fb")
    private val PCE_UUID: UUID = UUID.fromString("0000112e-0000-1000-8000-00805f9b34fb")
    private val MNS_TARGET = uuidBytes("bb582b41-420c-11db-b0de-0800200c9a66")

    @Volatile private var mns: BluetoothServerSocket? = null
    @Volatile private var pce: BluetoothServerSocket? = null

    /** New-message event from the iPhone (called on a background thread). */
    @Volatile var onEvent: (() -> Unit)? = null

    @Synchronized
    fun start(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        try {
            if (mns == null) {
                val server = adapter.listenUsingRfcommWithServiceRecord("MAP MNS-WearBridge", MNS_UUID)
                mns = server
                thread(name = "map-mns", isDaemon = true) { acceptLoop(server, ::serveMns) }
            }
            if (pce == null) {
                val server = adapter.listenUsingRfcommWithServiceRecord("Phonebook Access PCE", PCE_UUID)
                pce = server
                thread(name = "pbap-pce", isDaemon = true) { acceptLoop(server) { runCatching { it.close() } } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not publish MAP/PBAP records", e)
        }
    }

    @Synchronized
    fun stop() {
        runCatching { mns?.close() }
        runCatching { pce?.close() }
        mns = null
        pce = null
    }

    private fun acceptLoop(server: BluetoothServerSocket, handle: (BluetoothSocket) -> Unit) {
        while (true) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            thread(isDaemon = true) { runCatching { handle(socket) }; runCatching { socket.close() } }
        }
        synchronized(this) {
            if (mns === server) mns = null
            if (pce === server) pce = null
        }
    }

    /** OBEX server for MAP event reports: CONNECT, PUT (event body), DISCONNECT. */
    private fun serveMns(socket: BluetoothSocket) {
        val input = socket.inputStream
        val output = socket.outputStream
        val body = java.io.ByteArrayOutputStream()
        fun respond(code: Int, extra: ByteArray = ByteArray(0)) {
            val length = 3 + extra.size
            output.write(byteArrayOf(code.toByte(), (length shr 8).toByte(), length.toByte()) + extra)
            output.flush()
        }
        fun readFully(n: Int): ByteArray {
            val buf = ByteArray(n); var read = 0
            while (read < n) { val r = input.read(buf, read, n - read); if (r < 0) throw IOException("closed"); read += r }
            return buf
        }
        while (true) {
            val head = readFully(3)
            val opcode = head[0].toInt() and 0xFF
            val length = ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
            val rest = if (length > 3) readFully(length - 3) else ByteArray(0)
            when (opcode) {
                ObexClient.OP_CONNECT -> {
                    val who = ObexClient.Header(ObexClient.HI_WHO, MNS_TARGET).encode()
                    val connId = ObexClient.Header.int(ObexClient.HI_CONNECTION_ID, 1).encode()
                    respond(ObexClient.RSP_OK, byteArrayOf(0x10, 0x00, 0x04, 0x00) + connId + who)
                }
                ObexClient.OP_PUT, ObexClient.OP_PUT_FINAL -> {
                    ObexClient.parseHeaderBytes(rest, 0)
                        .filter { it.id == ObexClient.HI_BODY || it.id == ObexClient.HI_END_OF_BODY }
                        .forEach { body.write(it.bytes) }
                    if (opcode == ObexClient.OP_PUT) respond(ObexClient.RSP_CONTINUE)
                    else {
                        respond(ObexClient.RSP_OK)
                        val report = body.toString(Charsets.UTF_8.name())
                        body.reset()
                        if ("NewMessage" in report || "MessageShift" in report) onEvent?.invoke()
                    }
                }
                ObexClient.OP_DISCONNECT -> { respond(ObexClient.RSP_OK); return }
                else -> respond(0xD1) // Not implemented
            }
        }
    }
}
