package com.wearos.ancsbridge.messages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MessagingTest {

    private fun packet(code: Int, vararg headers: ObexClient.Header, prefix: ByteArray = ByteArray(0)): ByteArray {
        val encoded = headers.fold(ByteArray(0)) { acc, h -> acc + h.encode() }
        val length = 3 + prefix.size + encoded.size
        return byteArrayOf(code.toByte(), (length shr 8).toByte(), length.toByte()) + prefix + encoded
    }

    @Test
    fun `connect then GET follows continue responses`() {
        val connId = byteArrayOf(0, 0, 0, 7)
        val server = packet(0xA0, ObexClient.Header(ObexClient.HI_CONNECTION_ID, connId),
                prefix = byteArrayOf(0x10, 0, 0x04, 0x00)) +
            packet(0x90, ObexClient.Header(ObexClient.HI_BODY, "Hello ".toByteArray())) +
            packet(0xA0, ObexClient.Header(ObexClient.HI_END_OF_BODY, "world".toByteArray()))
        val sent = ByteArrayOutputStream()
        val obex = ObexClient(ByteArrayInputStream(server), sent)
        assertTrue(obex.connect(uuidBytes("bb582b40-420c-11db-b0de-0800200c9a66")))
        val response = obex.get("inbox", "x-bt/MAP-msg-listing")
        assertTrue(response.success)
        assertEquals("Hello world", response.body().toString(Charsets.UTF_8))
        val bytes = sent.toByteArray()
        assertEquals(0x80, bytes[0].toInt() and 0xFF)
        // The GET after CONNECT carries the connection id.
        val getStart = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        assertEquals(0x83, bytes[getStart].toInt() and 0xFF)
        assertEquals(ObexClient.HI_CONNECTION_ID, bytes[getStart + 3].toInt() and 0xFF)
    }

    @Test
    fun `unicode name header is UTF-16 with terminator`() {
        val encoded = ObexClient.Header.name("ab").encode()
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x09, 0, 'a'.code.toByte(), 0, 'b'.code.toByte(), 0, 0), encoded)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x03), ObexClient.Header.name(null).encode())
    }

    @Test
    fun `put splits large bodies`() {
        val server = packet(0xA0, prefix = byteArrayOf(0x10, 0, 0x01, 0x00)) + // max packet 256
            packet(0x90) + packet(0x90) + packet(0xA0, ObexClient.Header.name("handle1"))
        val sent = ByteArrayOutputStream()
        val obex = ObexClient(ByteArrayInputStream(server), sent)
        obex.connect(ByteArray(16))
        val response = obex.put("outbox", "x-bt/message", null, ByteArray(500) { 'x'.code.toByte() })
        assertTrue(response.success)
    }

    private val listing = """<?xml version="1.0"?>
<MAP-msg-listing version="1.0">
<msg handle="20000100001" subject="Tối nay đi ăn không &amp; xem phim?" datetime="20261127T201500+0700" sender_name="Minh" sender_addressing="+84 912 345 678" recipient_addressing="" type="SMS_GSM" read="no"/>
<msg handle="20000100002" subject="OK" datetime="20261127T020000" sender_name="Lan" sender_addressing="lan@icloud.com" type="SMS_GSM" read="yes" />
</MAP-msg-listing>"""

    @Test
    fun `parses iOS message listing`() {
        val messages = MapFormats.parseListing(listing, outgoing = false)
        assertEquals(2, messages.size)
        assertEquals("Tối nay đi ăn không & xem phim?", messages[0].subject)
        assertEquals("+84 912 345 678", messages[0].senderAddress)
        assertEquals(false, messages[0].read)
        assertEquals(MapFormats.parseDateTime("20261127T131500Z"), messages[0].timeMs)
    }

    @Test
    fun `finds the address to reply to an alert`() {
        val inbox = MapFormats.parseListing(listing, false)
        assertEquals("+84 912 345 678", MapFormats.addressForAlert(inbox, "Minh", "Tối nay đi ăn"))
        assertEquals("lan@icloud.com", MapFormats.addressForAlert(inbox, "Lan", ""))
        assertTrue(MapFormats.sameAddress("+84912345678", "0912 345 678"))
    }

    @Test
    fun `groups messages into conversations`() {
        val inbox = MapFormats.parseListing(listing, false)
        val sent = listOf(MapMessage("s1", "Đi!", inbox[0].timeMs + 1, "", "", "Minh", "0912345678", true, true))
        val conversations = MapFormats.conversations(inbox + sent,
            listOf(Contact("Minh Nguyễn", listOf("+84912345678"), emptyList())))
        assertEquals(2, conversations.size)
        assertEquals("Minh Nguyễn", conversations[0].name)
        assertEquals(2, conversations[0].messages.size)
        assertEquals(0, conversations[0].unread.let { if (conversations[0].last.outgoing) 0 else it })
    }

    @Test
    fun `bMessage declares the UTF-8 byte length of the message block`() {
        val bmsg = MapFormats.buildBMessage("+84912345678", "Chào bạn")
        val declared = Regex("LENGTH:(\\d+)").find(bmsg)!!.groupValues[1].toInt()
        val block = bmsg.substring(bmsg.indexOf("BEGIN:MSG"), bmsg.indexOf("END:MSG") + "END:MSG\r\n".length)
        assertEquals(block.toByteArray(Charsets.UTF_8).size, declared)
        assertTrue("TEL:+84912345678" in bmsg)
        assertEquals("Chào bạn", MapFormats.parseBMessageText(bmsg))
    }

    @Test
    fun `parses vCards including quoted-printable UTF-8`() {
        val vcf = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Nguy=E1=BB=85n;Minh;;;\r\n" +
            "TEL;CELL:+84 912 345 678\r\nEND:VCARD\r\n" +
            "BEGIN:VCARD\r\nVERSION:2.1\r\nFN:Lan\r\nEMAIL;INTERNET:lan@icloud.com\r\nEND:VCARD\r\n" +
            "BEGIN:VCARD\r\nVERSION:2.1\r\nFN:No Number\r\nEND:VCARD\r\n"
        val contacts = MapFormats.parseVCards(vcf)
        assertEquals(listOf("Lan", "Minh Nguyễn"), contacts.map { it.name })
        assertEquals(listOf("+84 912 345 678"), contacts[1].phones)
    }
}
