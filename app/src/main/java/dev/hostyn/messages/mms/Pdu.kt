package dev.hostyn.messages.mms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/*
 * Minimal MMS PDU codec (OMA MMS Encapsulation 1.2/1.3 over WAP-230 WSP encoding).
 *
 * It covers what a messaging app needs: reading notifications, retrieved messages, send
 * confirmations and delivery reports, and writing send requests and notify responses.
 */

object MessageType {
    const val SEND_REQ = 128
    const val SEND_CONF = 129
    const val NOTIFICATION_IND = 130
    const val NOTIFYRESP_IND = 131
    const val RETRIEVE_CONF = 132
    const val ACKNOWLEDGE_IND = 133
    const val DELIVERY_IND = 134
}

/** MMS header field codes, already with the high bit set as they appear on the wire. */
private object H {
    const val BCC = 0x81
    const val CC = 0x82
    const val CONTENT_LOCATION = 0x83
    const val CONTENT_TYPE = 0x84
    const val DATE = 0x85
    const val DELIVERY_REPORT = 0x86
    const val DELIVERY_TIME = 0x87
    const val EXPIRY = 0x88
    const val FROM = 0x89
    const val MESSAGE_CLASS = 0x8A
    const val MESSAGE_ID = 0x8B
    const val MESSAGE_TYPE = 0x8C
    const val MMS_VERSION = 0x8D
    const val MESSAGE_SIZE = 0x8E
    const val READ_REPORT = 0x90
    const val REPORT_ALLOWED = 0x91
    const val RESPONSE_STATUS = 0x92
    const val RESPONSE_TEXT = 0x93
    const val STATUS = 0x95
    const val SUBJECT = 0x96
    const val TO = 0x97
    const val TRANSACTION_ID = 0x98
    const val RETRIEVE_STATUS = 0x99
    const val RETRIEVE_TEXT = 0x9A
}

object MmsStatus {
    /** X-Mms-Response-Status: Ok. */
    const val RESPONSE_OK = 0x80
    /** X-Mms-Status values (delivery reports and notify responses). */
    const val EXPIRED = 0x80
    const val RETRIEVED = 0x81
    const val REJECTED = 0x82
    const val DEFERRED = 0x83
    const val UNRECOGNISED = 0x84
    const val FORWARDED = 0x86
    const val UNREACHABLE = 0x87
}

/** MMS version 1.2, encoded as a short integer (major << 4 | minor). */
const val MMS_VERSION_1_2 = 0x12

const val CHARSET_UTF8 = 106

class PduPart(
    val contentType: String,
    val data: ByteArray,
    val name: String? = null,
    val contentId: String? = null,
    val contentLocation: String? = null,
    val charset: Int? = null,
) {
    val isText get() = contentType.startsWith("text/plain", ignoreCase = true)
    val isSmil get() = contentType.equals("application/smil", ignoreCase = true)

    fun text(): String = String(data, charsetFor(charset ?: CHARSET_UTF8))
}

class Pdu(
    val type: Int,
    val transactionId: String? = null,
    val version: Int = MMS_VERSION_1_2,
    val from: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String? = null,
    /** Seconds since the epoch. */
    val date: Long? = null,
    val messageId: String? = null,
    val contentLocation: String? = null,
    val messageSize: Long? = null,
    /** Absolute expiry in seconds since the epoch, or relative seconds when [expiryRelative]. */
    val expiry: Long? = null,
    val expiryRelative: Boolean = false,
    val contentType: String? = null,
    val responseStatus: Int? = null,
    val status: Int? = null,
    val parts: List<PduPart> = emptyList(),
)

/** Address as shown to people: "+34600111222/TYPE=PLMN" → "+34600111222". */
fun cleanAddress(address: String): String = address.substringBefore("/TYPE=").trim()

// --- Charsets ---

private val charsets = mapOf(
    3 to "US-ASCII",
    4 to "ISO-8859-1",
    106 to "UTF-8",
    1000 to "UTF-16BE",
    1013 to "UTF-16BE",
    1014 to "UTF-16LE",
    1015 to "UTF-16",
    2026 to "Big5",
    17 to "Shift_JIS",
    2025 to "GB2312",
    113 to "GBK",
    114 to "GB18030",
    36 to "EUC-KR",
)

internal fun charsetFor(mib: Int): Charset =
    charsets[mib]?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

private fun mibFor(name: String): Int =
    charsets.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key
        ?: CHARSET_UTF8

// --- Well-known content types (WAP-230 Table 40) ---

private val wellKnownTypes = listOf(
    "*/*", "text/*", "text/html", "text/plain", "text/x-hdml", "text/x-ttml", "text/x-vCalendar",
    "text/x-vCard", "text/vnd.wap.wml", "text/vnd.wap.wmlscript", "text/vnd.wap.wta-event", "multipart/*",
    "multipart/mixed", "multipart/form-data", "multipart/byterantes", "multipart/alternative",
    "application/*", "application/java-vm", "application/x-www-form-urlencoded", "application/x-hdmlc",
    "application/vnd.wap.wmlc", "application/vnd.wap.wmlscriptc", "application/vnd.wap.wta-eventc",
    "application/vnd.wap.uaprof", "application/vnd.wap.wtls-ca-certificate",
    "application/vnd.wap.wtls-user-certificate", "application/x-x509-ca-cert",
    "application/x-x509-user-cert", "image/*", "image/gif", "image/jpeg", "image/tiff", "image/png",
    "image/vnd.wap.wbmp", "application/vnd.wap.multipart.*", "application/vnd.wap.multipart.mixed",
    "application/vnd.wap.multipart.form-data", "application/vnd.wap.multipart.byteranges",
    "application/vnd.wap.multipart.alternative", "application/xml", "text/xml", "application/vnd.wap.wbxml",
    "application/x-x968-cross-cert", "application/x-x968-ca-cert", "application/x-x968-user-cert",
    "text/vnd.wap.si", "application/vnd.wap.sic", "text/vnd.wap.sl", "application/vnd.wap.slc",
    "text/vnd.wap.co", "application/vnd.wap.coc", "application/vnd.wap.multipart.related",
    "application/vnd.wap.sia", "text/vnd.wap.connectivity-xml", "application/vnd.wap.connectivity-wbxml",
    "application/pkcs7-mime", "application/vnd.wap.hashed-certificate", "application/vnd.wap.signed-certificate",
    "application/vnd.wap.cert-response", "application/xhtml+xml", "application/wml+xml", "text/css",
    "application/vnd.wap.mms-message",
)

const val CT_MULTIPART_RELATED = "application/vnd.wap.multipart.related"
const val CT_MULTIPART_MIXED = "application/vnd.wap.multipart.mixed"

// ---------------------------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------------------------

class PduException(message: String) : Exception(message)

object PduParser {
    /** Parses a PDU, or throws [PduException] if it's malformed. */
    fun parse(bytes: ByteArray): Pdu = try {
        Reader(bytes).pdu()
    } catch (e: IndexOutOfBoundsException) {
        throw PduException("Truncated PDU")
    }

    fun parseOrNull(bytes: ByteArray): Pdu? = try {
        parse(bytes)
    } catch (_: Exception) {
        null
    }
}

private class Reader(private val b: ByteArray) {
    var pos = 0

    fun peek(): Int = b[pos].toInt() and 0xFF
    fun u8(): Int = b[pos++].toInt() and 0xFF
    fun hasMore() = pos < b.size

    fun uintvar(): Long {
        var value = 0L
        var count = 0
        do {
            val x = u8()
            value = (value shl 7) or (x and 0x7F).toLong()
            if (++count > 5) throw PduException("Bad uintvar")
        } while (x and 0x80 != 0)
        return value
    }

    fun valueLength(): Int {
        val x = u8()
        return when {
            x <= 30 -> x
            x == 31 -> uintvar().toInt()
            else -> throw PduException("Bad value length")
        }
    }

    fun bytesUntilNull(): ByteArray {
        val start = pos
        while (b[pos] != 0.toByte()) pos++
        return b.copyOfRange(start, pos++)
    }

    /** Text-string or Quoted-string. */
    fun text(charset: Charset = Charsets.UTF_8): String {
        if (peek() == 0x7F || peek() == 0x22) pos++
        return String(bytesUntilNull(), charset)
    }

    fun longInteger(): Long {
        val len = u8()
        if (len > 8) throw PduException("Long integer too long")
        var v = 0L
        repeat(len) { v = (v shl 8) or u8().toLong() }
        return v
    }

    /** Short-integer or Long-integer. */
    fun integer(): Long = if (peek() >= 0x80) (u8() and 0x7F).toLong() else longInteger()

    /** Encoded-string-value: Text-string, or Value-length Charset Text-string. */
    fun encodedString(): String {
        if (peek() > 31) return text()
        val len = valueLength()
        val end = pos + len
        val mib = integer().toInt()
        val charset = charsetFor(mib)
        val raw = if (pos < end) {
            if (peek() == 0x7F) pos++
            val start = pos
            while (pos < end && b[pos] != 0.toByte()) pos++
            b.copyOfRange(start, pos)
        } else ByteArray(0)
        pos = end
        return String(raw, charset)
    }

    /** Skips any WSP-encoded value. */
    fun skipValue() {
        val x = peek()
        when {
            x <= 30 -> pos += 1 + x
            x == 31 -> { pos++; val n = uintvar().toInt(); pos += n }
            x in 32..127 -> bytesUntilNull()
            else -> pos++
        }
    }

    class ContentType(val type: String, val params: Map<String, String>)

    fun contentType(): ContentType {
        val x = peek()
        if (x >= 0x80) return ContentType(wellKnown(u8() and 0x7F), emptyMap())
        if (x in 32..127) return ContentType(text(), emptyMap())
        val len = valueLength()
        val end = pos + len
        val media = when {
            peek() >= 0x80 -> wellKnown(u8() and 0x7F)
            peek() <= 30 -> wellKnown(longInteger().toInt())
            else -> text()
        }
        val params = mutableMapOf<String, String>()
        while (pos < end) {
            val key = peek()
            if (key >= 0x80) {
                pos++
                when (key and 0x7F) {
                    0x01 -> params["charset"] = if (peek() in 32..127) mibFor(text()).toString() else integer().toString()
                    0x03, 0x09 -> params["type"] = if (peek() >= 0x80) wellKnown(u8() and 0x7F) else text()
                    0x05, 0x17 -> params["name"] = text()
                    0x06, 0x18 -> params["filename"] = text()
                    0x0A, 0x19 -> params["start"] = text()
                    else -> skipValue()
                }
            } else {
                val name = text().lowercase()
                params[name] = when {
                    peek() >= 0x80 -> (u8() and 0x7F).toString()
                    peek() <= 30 -> longInteger().toString()
                    else -> text()
                }
            }
        }
        pos = end
        return ContentType(media, params)
    }

    private fun wellKnown(code: Int) = wellKnownTypes.getOrNull(code) ?: "application/octet-stream"

    fun pdu(): Pdu {
        var type = 0
        var tid: String? = null
        var version = MMS_VERSION_1_2
        var from: String? = null
        val to = mutableListOf<String>()
        val cc = mutableListOf<String>()
        var subject: String? = null
        var date: Long? = null
        var messageId: String? = null
        var location: String? = null
        var size: Long? = null
        var expiry: Long? = null
        var expiryRelative = false
        var contentType: String? = null
        var responseStatus: Int? = null
        var status: Int? = null
        var parts = emptyList<PduPart>()

        while (hasMore()) {
            val h = u8()
            if (h < 0x80) {
                // Application header: Token-text Application-specific-value.
                pos--
                text(); text()
                continue
            }
            when (h) {
                H.MESSAGE_TYPE -> type = u8()
                H.TRANSACTION_ID -> tid = text()
                H.MMS_VERSION -> version = integer().toInt()
                H.FROM -> {
                    val len = valueLength()
                    val end = pos + len
                    if (u8() == 0x80) from = cleanAddress(encodedString())
                    pos = end
                }
                H.TO -> to += cleanAddress(encodedString())
                H.CC -> cc += cleanAddress(encodedString())
                H.BCC -> encodedString()
                H.SUBJECT -> subject = encodedString()
                H.RESPONSE_TEXT, H.RETRIEVE_TEXT -> encodedString()
                H.DATE -> date = longInteger()
                H.MESSAGE_ID -> messageId = text()
                H.CONTENT_LOCATION -> location = text()
                H.MESSAGE_SIZE -> size = longInteger()
                H.EXPIRY, H.DELIVERY_TIME -> {
                    val len = valueLength()
                    val end = pos + len
                    val token = u8()
                    val value = integer()
                    if (h == H.EXPIRY) {
                        expiry = value
                        expiryRelative = token == 0x81
                    }
                    pos = end
                }
                H.MESSAGE_CLASS -> if (peek() >= 0x80) pos++ else text()
                H.RESPONSE_STATUS -> responseStatus = u8()
                H.STATUS -> status = u8()
                H.RETRIEVE_STATUS -> u8()
                H.CONTENT_TYPE -> {
                    val ct = contentType()
                    contentType = ct.type
                    // The content type is always the last header; the body follows.
                    if (ct.type.startsWith("application/vnd.wap.multipart", ignoreCase = true)) {
                        parts = multipart()
                    } else if (hasMore()) {
                        val charset = ct.params["charset"]?.toIntOrNull()
                        parts = listOf(PduPart(ct.type, b.copyOfRange(pos, b.size), charset = charset))
                    }
                    pos = b.size
                }
                else -> skipValue()
            }
        }
        if (type == 0) throw PduException("Missing message type")
        return Pdu(
            type = type, transactionId = tid, version = version, from = from, to = to, cc = cc,
            subject = subject, date = date, messageId = messageId, contentLocation = location,
            messageSize = size, expiry = expiry, expiryRelative = expiryRelative, contentType = contentType,
            responseStatus = responseStatus, status = status, parts = parts,
        )
    }

    private fun multipart(): List<PduPart> {
        if (!hasMore()) return emptyList()
        val count = uintvar().toInt()
        val parts = mutableListOf<PduPart>()
        repeat(count) {
            if (!hasMore()) return parts
            val headersLen = uintvar().toInt()
            val dataLen = uintvar().toInt()
            val headersEnd = pos + headersLen
            val ct = contentType()
            var contentId: String? = null
            var location: String? = null
            var name = ct.params["name"] ?: ct.params["filename"]
            while (pos < headersEnd) {
                val h = peek()
                if (h >= 0x80) {
                    pos++
                    when (h and 0x7F) {
                        0x0E -> location = text()
                        0x40 -> contentId = text()
                        0x2E, 0x45 -> {
                            // Content-Disposition: Value-length Disposition *(Parameter)
                            val len = valueLength()
                            val end = pos + len
                            pos++
                            while (pos < end) {
                                val p = peek()
                                if (p == 0x86 || p == 0x98 || p == 0x85 || p == 0x97) {
                                    pos++
                                    name = name ?: text()
                                } else {
                                    pos = end
                                }
                            }
                            pos = end
                        }
                        else -> skipValue()
                    }
                } else {
                    val headerName = text()
                    val value = text()
                    when {
                        headerName.equals("Content-ID", true) -> contentId = value
                        headerName.equals("Content-Location", true) -> location = value
                    }
                }
            }
            pos = headersEnd
            val data = b.copyOfRange(pos, pos + dataLen)
            pos += dataLen
            parts += PduPart(
                contentType = ct.type,
                data = data,
                name = name ?: location,
                contentId = contentId?.removeSurrounding("<", ">"),
                contentLocation = location,
                charset = ct.params["charset"]?.toIntOrNull(),
            )
        }
        return parts
    }
}

// ---------------------------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------------------------

object PduComposer {
    /** An m-send-req for [to] (phone numbers or emails) with [parts], as a multipart/related message. */
    fun sendRequest(
        transactionId: String,
        to: List<String>,
        parts: List<PduPart>,
        subject: String? = null,
        deliveryReport: Boolean = false,
    ): ByteArray = Writer().apply {
        header(H.MESSAGE_TYPE); short(MessageType.SEND_REQ)
        header(H.TRANSACTION_ID); text(transactionId)
        header(H.MMS_VERSION); short(MMS_VERSION_1_2)
        // From: Value-length Insert-address-token (the MMSC fills in our number).
        header(H.FROM); byte(1); byte(0x81)
        to.forEach { header(H.TO); encodedString(networkAddress(it)) }
        if (!subject.isNullOrBlank()) { header(H.SUBJECT); encodedString(subject) }
        header(H.MESSAGE_CLASS); byte(0x80) // Personal
        header(H.DELIVERY_REPORT); byte(if (deliveryReport) 0x80 else 0x81)
        header(H.READ_REPORT); byte(0x81)

        val smil = parts.firstOrNull { it.isSmil }
        header(H.CONTENT_TYPE)
        withLength {
            short(wellKnownTypes.indexOf(CT_MULTIPART_RELATED))
            if (smil != null) {
                byte(0x8A); text("<${smil.contentId ?: "smil"}>") // Start
                byte(0x89); text("application/smil") // Type
            }
        }
        multipart(parts)
    }.bytes()

    /** An m-notifyresp-ind telling the MMSC the message was downloaded. */
    fun notifyResponse(transactionId: String, status: Int = MmsStatus.RETRIEVED): ByteArray = Writer().apply {
        header(H.MESSAGE_TYPE); short(MessageType.NOTIFYRESP_IND)
        header(H.TRANSACTION_ID); text(transactionId)
        header(H.MMS_VERSION); short(MMS_VERSION_1_2)
        header(H.STATUS); byte(status)
        header(H.REPORT_ALLOWED); byte(0x81)
    }.bytes()

    /** Phone numbers need the PLMN type suffix; email addresses go as they are. */
    private fun networkAddress(address: String): String =
        if ('@' in address) address else address.filter { it.isDigit() || it == '+' } + "/TYPE=PLMN"

    private fun Writer.multipart(parts: List<PduPart>) {
        uintvar(parts.size.toLong())
        parts.forEach { part ->
            val headers = Writer().apply {
                val params = Writer().apply {
                    val code = wellKnownTypes.indexOfFirst { it.equals(part.contentType, ignoreCase = true) }
                    if (code >= 0) short(code) else text(part.contentType)
                    if (part.charset != null) { byte(0x81); short(part.charset) }
                    if (part.name != null) { byte(0x85); text(part.name) }
                }.bytes()
                valueLength(params.size); bytes(params)
                if (part.contentId != null) { byte(0xC0); quoted("<${part.contentId}>") }
                if (part.contentLocation != null) { byte(0x8E); text(part.contentLocation) }
            }.bytes()
            uintvar(headers.size.toLong())
            uintvar(part.data.size.toLong())
            bytes(headers)
            bytes(part.data)
        }
    }
}

private class Writer {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()
    fun byte(v: Int) = out.write(v and 0xFF)
    fun bytes(v: ByteArray) = out.write(v)
    fun header(code: Int) = byte(code)
    fun short(v: Int) = byte(0x80 or (v and 0x7F))

    fun uintvar(value: Long) {
        var v = value
        val tmp = ArrayList<Int>()
        tmp += (v and 0x7F).toInt()
        v = v shr 7
        while (v > 0) {
            tmp += (0x80 or (v and 0x7F).toInt())
            v = v shr 7
        }
        tmp.asReversed().forEach { byte(it) }
    }

    fun valueLength(n: Int) {
        if (n <= 30) byte(n) else { byte(31); uintvar(n.toLong()) }
    }

    fun text(s: String) {
        val raw = s.toByteArray(Charsets.UTF_8)
        if (raw.isNotEmpty() && (raw[0].toInt() and 0xFF) >= 0x80) byte(0x7F)
        bytes(raw); byte(0)
    }

    fun quoted(s: String) {
        byte(0x22); bytes(s.toByteArray(Charsets.UTF_8)); byte(0)
    }

    /** ASCII goes as a plain Text-string; anything else is tagged as UTF-8. */
    fun encodedString(s: String) {
        if (s.all { it.code < 0x80 }) return text(s)
        withLength {
            short(CHARSET_UTF8)
            text(s)
        }
    }

    /** Writes Value-length followed by whatever [block] writes. */
    fun withLength(block: Writer.() -> Unit) {
        val inner = Writer().apply(block).bytes()
        valueLength(inner.size)
        bytes(inner)
    }
}
