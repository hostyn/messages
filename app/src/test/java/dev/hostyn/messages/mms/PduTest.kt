package dev.hostyn.messages.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PduTest {
    /** Tiny helper to write PDUs by hand, byte by byte, as the network sends them. */
    private class Bytes {
        val out = ByteArrayOutputStream()
        fun b(vararg v: Int) = apply { v.forEach { out.write(it) } }
        fun s(text: String) = apply { out.write(text.toByteArray(Charsets.UTF_8)); out.write(0) }
        fun raw(data: ByteArray) = apply { out.write(data) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    @Test
    fun sendRequestRoundTrip() {
        val image = ByteArray(300) { it.toByte() }
        val parts = listOf(
            PduPart("application/smil", "<smil/>".toByteArray(), contentId = "smil", contentLocation = "smil.xml"),
            PduPart("image/jpeg", image, name = "photo.jpg", contentId = "image0", contentLocation = "photo.jpg"),
            PduPart("text/plain", "¡Hola, qué tal! 👋".toByteArray(), contentId = "text0", contentLocation = "text0.txt", charset = CHARSET_UTF8),
        )
        val bytes = PduComposer.sendRequest(
            transactionId = "T123",
            to = listOf("+34 600 11 22 33", "user@example.com"),
            parts = parts,
            subject = "Café",
            deliveryReport = true,
        )
        val pdu = PduParser.parse(bytes)

        assertEquals(MessageType.SEND_REQ, pdu.type)
        assertEquals("T123", pdu.transactionId)
        assertEquals(MMS_VERSION_1_2, pdu.version)
        assertNull(pdu.from) // Insert-address token: filled in by the MMSC.
        assertEquals(listOf("+34600112233", "user@example.com"), pdu.to)
        assertEquals("Café", pdu.subject)
        assertEquals(CT_MULTIPART_RELATED, pdu.contentType)
        assertEquals(3, pdu.parts.size)

        val (smil, img, text) = pdu.parts
        assertTrue(smil.isSmil)
        assertEquals("smil", smil.contentId)
        assertEquals("image/jpeg", img.contentType)
        assertEquals("photo.jpg", img.name)
        assertEquals("image0", img.contentId)
        assertArrayEquals(image, img.data)
        assertTrue(text.isText)
        assertEquals(CHARSET_UTF8, text.charset)
        assertEquals("¡Hola, qué tal! 👋", text.text())
    }

    @Test
    fun asciiSubjectIsPlainText() {
        val bytes = PduComposer.sendRequest("T", listOf("600111222"), emptyList(), subject = "Hi")
        assertEquals("Hi", PduParser.parse(bytes).subject)
    }

    @Test
    fun notifyResponse() {
        val pdu = PduParser.parse(PduComposer.notifyResponse("abc"))
        assertEquals(MessageType.NOTIFYRESP_IND, pdu.type)
        assertEquals("abc", pdu.transactionId)
        assertEquals(MmsStatus.RETRIEVED, pdu.status)
    }

    @Test
    fun parsesNotificationInd() {
        val bytes = Bytes()
            .b(0x8C, 0x82) // Message-Type: m-notification-ind
            .b(0x98).s("tid-42") // Transaction-Id
            .b(0x8D, 0x92) // MMS-Version 1.2
            .b(0x89, "+34600/TYPE=PLMN".length + 2, 0x80).s("+34600/TYPE=PLMN") // From: length, address-present
            .b(0x96).s("Fotos") // Subject
            .b(0x8A, 0x80) // Message-Class: personal
            .b(0x8E, 0x02, 0x9C, 0x40) // Message-Size: 40000
            .b(0x88, 0x03, 0x81, 0x01, 0x80) // Expiry: relative, long integer 128 s
            .b(0x83).s("http://mmsc.example/abc") // Content-Location
            .bytes()
        val pdu = PduParser.parse(bytes)
        assertEquals(MessageType.NOTIFICATION_IND, pdu.type)
        assertEquals("tid-42", pdu.transactionId)
        assertEquals("+34600", pdu.from)
        assertEquals("Fotos", pdu.subject)
        assertEquals(40000L, pdu.messageSize)
        assertEquals(128L, pdu.expiry)
        assertTrue(pdu.expiryRelative)
        assertEquals("http://mmsc.example/abc", pdu.contentLocation)
        assertTrue(pdu.parts.isEmpty())
    }

    @Test
    fun parsesRetrieveConf() {
        val text = "Hola ñ".toByteArray(Charsets.UTF_8)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val textHeaders = Bytes()
            .b(0x03, 0x83, 0x81, 0xEA) // Content-Type: length 3, text/plain, charset=utf-8
            .b(0xC0, 0x22).s("<t1>") // Content-ID (quoted)
            .bytes()
        val imageHeaders = Bytes()
            .b(0x9E) // Content-Type: image/jpeg
            .b(0x8E).s("img.jpg") // Content-Location
            .bytes()
        val subject = Bytes().b(0xEA).s("¿Qué?").bytes()
        val bytes = Bytes()
            .b(0x8C, 0x84) // m-retrieve-conf
            .b(0x98).s("tid")
            .b(0x8D, 0x92)
            .b(0x8B).s("msg-id-1") // Message-ID
            .b(0x85, 0x04, 0x65, 0x00, 0x00, 0x00) // Date
            .b(0x89, "600111/TYPE=PLMN".length + 2, 0x80).s("600111/TYPE=PLMN")
            .b(0x97).s("+34700/TYPE=PLMN") // To
            .b(0x82).s("+34800/TYPE=PLMN") // Cc
            .b(0x96, subject.size).raw(subject) // Subject with charset
            .b(0x84, 0x01, 0xB3) // Content-Type: multipart.related
            .b(0x02) // Two parts
            .b(textHeaders.size, text.size).raw(textHeaders).raw(text)
            .b(imageHeaders.size, jpeg.size).raw(imageHeaders).raw(jpeg)
            .bytes()
        val pdu = PduParser.parse(bytes)
        assertEquals(MessageType.RETRIEVE_CONF, pdu.type)
        assertEquals("msg-id-1", pdu.messageId)
        assertEquals(0x65000000L, pdu.date)
        assertEquals("600111", pdu.from)
        assertEquals(listOf("+34700"), pdu.to)
        assertEquals(listOf("+34800"), pdu.cc)
        assertEquals("¿Qué?", pdu.subject)
        assertEquals(CT_MULTIPART_RELATED, pdu.contentType)
        assertEquals(2, pdu.parts.size)
        assertEquals("Hola ñ", pdu.parts[0].text())
        assertEquals("t1", pdu.parts[0].contentId)
        assertEquals("image/jpeg", pdu.parts[1].contentType)
        assertEquals("img.jpg", pdu.parts[1].contentLocation)
        assertArrayEquals(jpeg, pdu.parts[1].data)
    }

    @Test
    fun malformedPduIsRejected() {
        assertNull(PduParser.parseOrNull(byteArrayOf(0x98.toByte(), 'a'.code.toByte())))
        assertNull(PduParser.parseOrNull(byteArrayOf()))
        assertFalse(PduParser.parseOrNull(byteArrayOf(0x8C.toByte(), 0x80.toByte())) == null)
    }

    @Test
    fun cleansAddresses() {
        assertEquals("+34600111222", cleanAddress("+34600111222/TYPE=PLMN"))
        assertEquals("a@b.c", cleanAddress("a@b.c"))
    }
}
