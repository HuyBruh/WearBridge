package com.wearos.ancsbridge.messages

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal OBEX client (IrOBEX 1.5 as used by Bluetooth MAP and PBAP): CONNECT,
 * SETPATH, GET and PUT over an already-open RFCOMM stream.
 */
class ObexClient(private val input: InputStream, private val output: OutputStream) {

    companion object {
        const val OP_CONNECT = 0x80
        const val OP_DISCONNECT = 0x81
        const val OP_PUT = 0x02
        const val OP_PUT_FINAL = 0x82
        const val OP_GET_FINAL = 0x83
        const val OP_SETPATH = 0x85

        const val RSP_CONTINUE = 0x90
        const val RSP_OK = 0xA0

        const val HI_NAME = 0x01
        const val HI_TYPE = 0x42
        const val HI_LENGTH = 0xC3
        const val HI_TARGET = 0x46
        const val HI_WHO = 0x4A
        const val HI_APP_PARAMS = 0x4C
        const val HI_BODY = 0x48
        const val HI_END_OF_BODY = 0x49
        const val HI_CONNECTION_ID = 0xCB

        private const val OUR_MAX_PACKET = 0x2000
    }

    /** One OBEX header: id + value (unicode text, byte sequence, 1 or 4 byte number). */
    class Header(val id: Int, val bytes: ByteArray) {
        companion object {
            fun name(value: String?): Header {
                if (value == null || value.isEmpty()) return Header(HI_NAME, ByteArray(0))
                return Header(HI_NAME, (value + "\u0000").toByteArray(Charsets.UTF_16BE))
            }
            fun type(value: String) = Header(HI_TYPE, (value + "\u0000").toByteArray(Charsets.US_ASCII))
            fun bytes(id: Int, value: ByteArray) = Header(id, value)
            fun int(id: Int, value: Long) = Header(id, byteArrayOf(
                (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()
            ))
        }

        fun encode(): ByteArray = when (id and 0xC0) {
            0x00, 0x40 -> { // unicode / byte sequence: id, 2-byte length (incl. 3 header bytes), data
                val length = bytes.size + 3
                byteArrayOf(id.toByte(), (length shr 8).toByte(), length.toByte()) + bytes
            }
            0x80 -> byteArrayOf(id.toByte(), bytes[0])
            else -> byteArrayOf(id.toByte()) + bytes
        }
    }

    class Response(val code: Int, val headers: List<Header>) {
        val success: Boolean get() = code == RSP_OK
        fun header(id: Int): ByteArray? = headers.firstOrNull { it.id == id }?.bytes
        fun body(): ByteArray {
            val out = ByteArrayOutputStream()
            headers.filter { it.id == HI_BODY || it.id == HI_END_OF_BODY }.forEach { out.write(it.bytes) }
            return out.toByteArray()
        }
    }

    private var connectionId: ByteArray? = null
    private var maxPacket = 255

    private fun connectionHeader(): List<Header> =
        connectionId?.let { listOf(Header(HI_CONNECTION_ID, it)) } ?: emptyList()

    /** Sends CONNECT to [target] (service UUID bytes). */
    fun connect(target: ByteArray): Boolean {
        val headers = Header(HI_TARGET, target).encode()
        val length = 7 + headers.size
        val packet = byteArrayOf(
            OP_CONNECT.toByte(), (length shr 8).toByte(), length.toByte(),
            0x10, 0x00, (OUR_MAX_PACKET shr 8).toByte(), OUR_MAX_PACKET.toByte()
        ) + headers
        output.write(packet)
        output.flush()
        val response = readResponse(connectResponse = true)
        if (!response.success) return false
        connectionId = response.header(HI_CONNECTION_ID)
        return true
    }

    /** SETPATH: [name] = child folder, null = root; [up] goes to the parent first. */
    fun setPath(name: String?, up: Boolean = false): Boolean {
        val flags = (if (up) 0x01 else 0x00) or 0x02 // never create folders
        val headers = connectionHeader() + Header.name(name)
        val response = send(OP_SETPATH, headers, prefix = byteArrayOf(flags.toByte(), 0x00))
        return response.success
    }

    /** GET an object; follows CONTINUE responses and concatenates the body. */
    fun get(name: String?, type: String, appParams: ByteArray? = null): Response {
        val headers = buildList {
            addAll(connectionHeader())
            if (name != null) add(Header.name(name))
            add(Header.type(type))
            if (appParams != null && appParams.isNotEmpty()) add(Header(HI_APP_PARAMS, appParams))
        }
        val collected = mutableListOf<Header>()
        var response = send(OP_GET_FINAL, headers)
        collected += response.headers
        while (response.code == RSP_CONTINUE) {
            response = send(OP_GET_FINAL, connectionHeader())
            collected += response.headers
        }
        return Response(response.code, collected)
    }

    /** PUT an object, splitting the body across packets as the server's max size allows. */
    fun put(name: String?, type: String, appParams: ByteArray?, body: ByteArray): Response {
        val first = buildList {
            addAll(connectionHeader())
            add(Header.name(name))
            add(Header.type(type))
            if (appParams != null && appParams.isNotEmpty()) add(Header(HI_APP_PARAMS, appParams))
            add(Header.int(HI_LENGTH, body.size.toLong()))
        }
        var offset = 0
        var headers = first
        while (true) {
            val used = 3 + headers.sumOf { it.encode().size } + 3
            val room = (maxPacket - used).coerceAtLeast(16)
            val end = minOf(body.size, offset + room)
            val last = end == body.size
            val chunk = body.copyOfRange(offset, end)
            val all = headers + Header(if (last) HI_END_OF_BODY else HI_BODY, chunk)
            val response = send(if (last) OP_PUT_FINAL else OP_PUT, all)
            if (last || response.code != RSP_CONTINUE) return response
            offset = end
            headers = connectionHeader()
        }
    }

    fun disconnect() {
        runCatching { send(OP_DISCONNECT, connectionHeader()) }
    }

    private fun send(opcode: Int, headers: List<Header>, prefix: ByteArray = ByteArray(0)): Response {
        val encoded = headers.fold(ByteArray(0)) { acc, h -> acc + h.encode() }
        val length = 3 + prefix.size + encoded.size
        output.write(byteArrayOf(opcode.toByte(), (length shr 8).toByte(), length.toByte()) + prefix + encoded)
        output.flush()
        return readResponse(connectResponse = false)
    }

    private fun readFully(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw IOException("OBEX stream closed")
            read += n
        }
        return buffer
    }

    private fun readResponse(connectResponse: Boolean): Response {
        val head = readFully(3)
        val code = head[0].toInt() and 0xFF
        val length = ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
        val rest = if (length > 3) readFully(length - 3) else ByteArray(0)
        var offset = 0
        if (connectResponse && rest.size >= 4) {
            maxPacket = (((rest[2].toInt() and 0xFF) shl 8) or (rest[3].toInt() and 0xFF)).coerceIn(255, OUR_MAX_PACKET)
            offset = 4
        }
        return Response(code, parseHeaders(rest, offset))
    }

    internal fun parseHeaders(data: ByteArray, start: Int): List<Header> {
        val headers = mutableListOf<Header>()
        var i = start
        while (i < data.size) {
            val id = data[i].toInt() and 0xFF
            when (id and 0xC0) {
                0x00, 0x40 -> {
                    if (i + 3 > data.size) break
                    val len = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
                    if (len < 3 || i + len > data.size) break
                    headers += Header(id, data.copyOfRange(i + 3, i + len))
                    i += len
                }
                0x80 -> { headers += Header(id, data.copyOfRange(i + 1, i + 2)); i += 2 }
                else -> { if (i + 5 > data.size) break; headers += Header(id, data.copyOfRange(i + 1, i + 5)); i += 5 }
            }
        }
        return headers
    }
}

/** Tag-length-value application parameters used by MAP and PBAP. */
class AppParams {
    private val out = ByteArrayOutputStream()
    fun byte(tag: Int, value: Int) = apply { out.write(tag); out.write(1); out.write(value) }
    fun short(tag: Int, value: Int) = apply { out.write(tag); out.write(2); out.write(value shr 8); out.write(value) }
    fun int(tag: Int, value: Long) = apply {
        out.write(tag); out.write(4)
        out.write((value shr 24).toInt()); out.write((value shr 16).toInt()); out.write((value shr 8).toInt()); out.write(value.toInt())
    }
    fun long(tag: Int, value: Long) = apply {
        out.write(tag); out.write(8)
        for (shift in 56 downTo 0 step 8) out.write((value shr shift).toInt())
    }
    fun build(): ByteArray = out.toByteArray()
}

internal fun uuidBytes(uuid: String): ByteArray =
    uuid.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
