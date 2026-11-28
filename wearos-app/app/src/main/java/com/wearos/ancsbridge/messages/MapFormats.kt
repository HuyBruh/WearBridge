package com.wearos.ancsbridge.messages

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One entry of a MAP message listing (x-bt/MAP-msg-listing). */
data class MapMessage(
    val handle: String,
    val subject: String,
    val timeMs: Long,
    val senderName: String,
    val senderAddress: String,
    val recipientName: String,
    val recipientAddress: String,
    val read: Boolean,
    /** True for messages from the "sent" folder. */
    val outgoing: Boolean,
) {
    /** The other person in the conversation. */
    val peerAddress: String get() = if (outgoing) recipientAddress else senderAddress
    val peerName: String get() = (if (outgoing) recipientName else senderName).ifBlank { peerAddress }
}

data class Contact(
    val name: String,
    val phones: List<String>,
    val emails: List<String>,
    /** JPEG/PNG bytes from the vCard PHOTO property; not cached in prefs (see [Avatars]). */
    val photo: ByteArray? = null,
) {
    val addresses: List<String> get() = phones + emails
}

data class Conversation(
    val address: String,
    val name: String,
    val messages: List<MapMessage>,
) {
    val last: MapMessage get() = messages.last()
    val unread: Int get() = messages.count { !it.outgoing && !it.read }
}

object MapFormats {

    // ---- Message listing --------------------------------------------------------------

    private val msgRegex = Regex("""<msg\s([^>]*?)/?>""", RegexOption.DOT_MATCHES_ALL)
    private val attrRegex = Regex("""([A-Za-z_]+)\s*=\s*"([^"]*)"""")

    fun parseListing(xml: String, outgoing: Boolean): List<MapMessage> =
        msgRegex.findAll(xml).mapNotNull { match ->
            val attrs = attrRegex.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase() to unescapeXml(it.groupValues[2]) }
            val handle = attrs["handle"] ?: return@mapNotNull null
            MapMessage(
                handle = handle,
                subject = attrs["subject"].orEmpty(),
                timeMs = parseDateTime(attrs["datetime"]),
                senderName = attrs["sender_name"].orEmpty(),
                senderAddress = attrs["sender_addressing"].orEmpty(),
                recipientName = attrs["recipient_name"].orEmpty(),
                recipientAddress = attrs["recipient_addressing"].orEmpty(),
                read = attrs["read"]?.equals("yes", ignoreCase = true) ?: true,
                outgoing = outgoing,
            )
        }.toList()

    fun unescapeXml(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

    /** MAP datetime: YYYYMMDDTHHMMSS, optionally followed by an offset like +0700 or Z. */
    fun parseDateTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        val base = value.take(15)
        return runCatching {
            val format = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
            val zone = value.drop(15)
            format.timeZone = when {
                zone.startsWith("Z") -> TimeZone.getTimeZone("UTC")
                zone.length >= 5 && (zone[0] == '+' || zone[0] == '-') ->
                    TimeZone.getTimeZone("GMT${zone.take(3)}:${zone.substring(3, 5)}")
                else -> TimeZone.getDefault()
            }
            format.parse(base)?.time ?: 0L
        }.getOrDefault(0L)
    }

    /** Groups inbox + sent listings into conversations, newest conversation first. */
    fun conversations(messages: List<MapMessage>, contacts: List<Contact> = emptyList()): List<Conversation> =
        messages.filter { it.peerAddress.isNotBlank() }
            .groupBy { normalize(it.peerAddress) }
            .map { (_, list) ->
                val sorted = list.sortedBy { it.timeMs }
                val address = sorted.last().peerAddress
                val name = contacts.firstOrNull { c -> c.addresses.any { sameAddress(it, address) } }?.name
                    ?: sorted.lastOrNull { !it.outgoing && it.senderName.isNotBlank() }?.senderName
                    ?: sorted.last().peerName
                Conversation(address, name, sorted)
            }
            .sortedByDescending { it.last.timeMs }

    /** Phone numbers compare on their last 9 digits; emails case-insensitively. */
    fun normalize(address: String): String =
        if ('@' in address) address.trim().lowercase()
        else address.filter { it.isDigit() }.takeLast(9)

    fun sameAddress(a: String, b: String): Boolean {
        val na = normalize(a)
        return na.isNotEmpty() && na == normalize(b)
    }

    /**
     * Finds the address to answer an alert: the newest inbox message whose sender name
     * matches the alert title (ANCS shows the contact name), preferring a matching text.
     */
    fun addressForAlert(inbox: List<MapMessage>, title: String, text: String): String? {
        val wanted = title.trim()
        val byName = inbox.filter { it.senderName.trim().equals(wanted, ignoreCase = true) }
            .sortedByDescending { it.timeMs }
        val snippet = text.trim().take(20)
        val best = byName.firstOrNull { snippet.isNotEmpty() && it.subject.startsWith(snippet) }
            ?: byName.firstOrNull()
            ?: inbox.sortedByDescending { it.timeMs }.firstOrNull {
                snippet.isNotEmpty() && it.subject.startsWith(snippet)
            }
            ?: inbox.firstOrNull { sameAddress(it.senderAddress, wanted) }
        return best?.senderAddress?.takeIf { it.isNotBlank() }
    }

    // ---- bMessage -----------------------------------------------------------------------

    /** bMessage for PushMessage (MAP 1.x). iOS chooses iMessage or SMS itself. */
    fun buildBMessage(address: String, text: String): String {
        val body = text.replace("\r\n", "\n").replace("\n", "\r\n")
        val message = "BEGIN:MSG\r\n$body\r\nEND:MSG\r\n"
        val length = message.toByteArray(Charsets.UTF_8).size
        val isEmail = '@' in address
        return buildString {
            append("BEGIN:BMSG\r\nVERSION:1.0\r\nSTATUS:READ\r\n")
            append(if (isEmail) "TYPE:EMAIL\r\n" else "TYPE:SMS_GSM\r\n")
            append("FOLDER:telecom/msg/outbox\r\n")
            append("BEGIN:BENV\r\n")
            append("BEGIN:VCARD\r\nVERSION:2.1\r\nN:\r\n")
            append(if (isEmail) "EMAIL:$address\r\n" else "TEL:$address\r\n")
            append("END:VCARD\r\n")
            append("BEGIN:BBODY\r\nCHARSET:UTF-8\r\nLENGTH:$length\r\n")
            append(message)
            append("END:BBODY\r\nEND:BENV\r\nEND:BMSG\r\n")
        }
    }

    /** Text of a bMessage returned by GetMessage. */
    fun parseBMessageText(bmsg: String): String {
        val start = bmsg.indexOf("BEGIN:MSG")
        val end = bmsg.lastIndexOf("END:MSG")
        if (start < 0 || end <= start) return ""
        return bmsg.substring(start + "BEGIN:MSG".length, end).trim('\r', '\n')
    }

    // ---- vCards (PBAP) ---------------------------------------------------------------------

    fun parseVCards(text: String): List<Contact> {
        // Unfold continuation lines (RFC 2425 folding and quoted-printable soft breaks).
        val unfolded = unfoldQuotedPrintable(text.replace(Regex("\r?\n[ \t]"), ""))
        val contacts = mutableListOf<Contact>()
        var name = ""
        var structured = ""
        var phones = mutableListOf<String>()
        var emails = mutableListOf<String>()
        var photo: ByteArray? = null
        var photoKey: String? = null
        val photoData = StringBuilder()
        for (raw in unfolded.lines()) {
            val line = raw.trimEnd('\r')
            val colon = line.indexOf(':')
            // vCard 2.1 base64 may continue on unindented lines until a blank line.
            if (photoKey != null && colon <= 0) {
                if (line.isBlank()) { photo = decodePhoto(photoKey!!, photoData.toString()); photoKey = null }
                else photoData.append(line.trim())
                continue
            }
            if (photoKey != null) { photo = decodePhoto(photoKey!!, photoData.toString()); photoKey = null }
            if (colon <= 0) continue
            val key = line.substring(0, colon)
            val value = decodeValue(key, line.substring(colon + 1))
            val property = key.substringBefore(';').uppercase()
            when (property) {
                "BEGIN" -> { name = ""; structured = ""; phones = mutableListOf(); emails = mutableListOf(); photo = null }
                "PHOTO" -> { photoKey = key; photoData.setLength(0); photoData.append(line.substring(colon + 1)) }
                "FN" -> name = value.trim()
                "N" -> structured = value.split(';').let { parts ->
                    listOfNotNull(parts.getOrNull(1), parts.getOrNull(2), parts.getOrNull(0))
                        .filter { it.isNotBlank() }.joinToString(" ")
                }
                "TEL" -> value.trim().takeIf { it.isNotEmpty() }?.let { phones += it }
                "EMAIL" -> value.trim().takeIf { it.isNotEmpty() }?.let { emails += it }
                "END" -> {
                    val display = name.ifBlank { structured }.ifBlank { phones.firstOrNull() ?: emails.firstOrNull() ?: "" }
                    if (display.isNotBlank() && (phones.isNotEmpty() || emails.isNotEmpty())) {
                        contacts += Contact(display, phones.distinct(), emails.distinct(), photo)
                    }
                }
            }
        }
        return contacts.distinctBy { it.name + it.addresses.joinToString() }.sortedBy { it.name.lowercase() }
    }

    /** Joins quoted-printable soft line breaks ("=" at line end), only on QP properties:
     *  base64 photo lines can legitimately end with "=" padding. */
    private fun unfoldQuotedPrintable(text: String): String {
        val out = StringBuilder()
        var joining = false
        for (raw in text.split("\n")) {
            val line = raw.trimEnd('\r')
            val isQp = joining || line.substringBefore(':').uppercase().contains("QUOTED-PRINTABLE")
            if (isQp && line.endsWith("=")) {
                out.append(line, 0, line.length - 1)
                joining = true
            } else {
                out.append(line).append('\n')
                joining = false
            }
        }
        return out.toString()
    }

    /** PHOTO;ENCODING=BASE64;TYPE=JPEG (2.1) or PHOTO;ENCODING=b;TYPE=JPEG (3.0); URLs are skipped. */
    internal fun decodePhoto(key: String, value: String): ByteArray? {
        val upper = key.uppercase()
        if ("VALUE=URI" in upper || "VALUE=URL" in upper) return null
        if ("BASE64" !in upper && "ENCODING=B" !in upper) return null
        val clean = value.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return null
        return runCatching { java.util.Base64.getMimeDecoder().decode(clean) }.getOrNull()?.takeIf { it.size > 64 }
    }

    private fun decodeValue(key: String, value: String): String {
        val upper = key.uppercase()
        if ("QUOTED-PRINTABLE" !in upper) return value
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '=' && i + 2 < value.length + 0 && i + 2 <= value.length - 1) {
                val hex = value.substring(i + 1, i + 3)
                val b = hex.toIntOrNull(16)
                if (b != null) { bytes.write(b); i += 3; continue }
            }
            bytes.write(c.code.coerceAtMost(0xFF)); i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}
