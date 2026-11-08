package dev.hostyn.messages.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony.Mms
import android.provider.Telephony.Threads
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import dev.hostyn.messages.MessagesApp
import dev.hostyn.messages.data.MessagesRepository.Companion.ADDR_CC
import dev.hostyn.messages.data.MessagesRepository.Companion.ADDR_FROM
import dev.hostyn.messages.data.MessagesRepository.Companion.ADDR_TO
import dev.hostyn.messages.data.MessagesRepository.Companion.INSERT_ADDRESS_TOKEN
import dev.hostyn.messages.data.MessagesRepository.Companion.MMS_STATUS_RETRIEVED
import dev.hostyn.messages.data.encodeMmsString
import dev.hostyn.messages.data.sameAddress
import dev.hostyn.messages.notify.Notifications
import dev.hostyn.messages.sms.smsManager
import kotlinx.coroutines.launch
import java.io.File

/** Download states kept in the STATUS column of a pending MMS (a notification). */
object DownloadState {
    const val IDLE = 0x80
    const val DOWNLOADING = 0x81
    const val FAILED = 0x82
}

private fun Context.fileUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

private fun mmsDir(context: Context) = File(context.cacheDir, "mms").apply { mkdirs() }

private fun mutableFlags() = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

/** Writes MMS into the Telephony provider (the default SMS app has to do it itself). */
object MmsStore {
    fun insert(
        context: Context,
        box: Int,
        messageType: Int,
        threadId: Long,
        from: String?,
        to: List<String>,
        cc: List<String> = emptyList(),
        parts: List<PduPart> = emptyList(),
        subject: String? = null,
        dateSeconds: Long = System.currentTimeMillis() / 1000,
        dateSentSeconds: Long? = null,
        read: Boolean,
        transactionId: String? = null,
        messageId: String? = null,
        contentLocation: String? = null,
        size: Long? = null,
        expiry: Long? = null,
        subId: Int,
        status: Int? = null,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(Mms.THREAD_ID, threadId)
            put(Mms.DATE, dateSeconds)
            dateSentSeconds?.let { put(Mms.DATE_SENT, it) }
            put(Mms.MESSAGE_BOX, box)
            put(Mms.READ, if (read) 1 else 0)
            put(Mms.SEEN, if (read) 1 else 0)
            put(Mms.MESSAGE_TYPE, messageType)
            put(Mms.MMS_VERSION, MMS_VERSION_1_2)
            put(Mms.CONTENT_TYPE, CT_MULTIPART_RELATED)
            put(Mms.MESSAGE_CLASS, "personal")
            put(Mms.PRIORITY, 129)
            put(Mms.DELIVERY_REPORT, 129)
            put(Mms.READ_REPORT, 129)
            transactionId?.let { put(Mms.TRANSACTION_ID, it) }
            messageId?.let { put(Mms.MESSAGE_ID, it) }
            contentLocation?.let { put(Mms.CONTENT_LOCATION, it) }
            size?.let { put(Mms.MESSAGE_SIZE, it) }
            expiry?.let { put(Mms.EXPIRY, it) }
            status?.let { put(Mms.STATUS, it) }
            if (!subject.isNullOrBlank()) {
                put(Mms.SUBJECT, encodeMmsString(subject))
                put(Mms.SUBJECT_CHARSET, CHARSET_UTF8)
            }
            put(Mms.SUBSCRIPTION_ID, subId)
            put(Mms.TEXT_ONLY, if (parts.all { it.isText || it.isSmil }) 1 else 0)
        }
        val uri = resolver.insert(Mms.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)

        parts.forEachIndexed { i, part ->
            val pv = ContentValues().apply {
                put(Mms.Part.MSG_ID, id)
                put(Mms.Part.SEQ, if (part.isSmil) -1 else i)
                put(Mms.Part.CONTENT_TYPE, part.contentType)
                part.contentId?.let { put(Mms.Part.CONTENT_ID, "<$it>") }
                part.contentLocation?.let { put(Mms.Part.CONTENT_LOCATION, it) }
                part.name?.let { put(Mms.Part.NAME, it); put(Mms.Part.FILENAME, it) }
                part.charset?.let { put(Mms.Part.CHARSET, it) }
                if (part.isText || part.isSmil) put(Mms.Part.TEXT, part.text())
            }
            val partUri = resolver.insert(Uri.parse("content://mms/$id/part"), pv) ?: return@forEachIndexed
            if (!part.isText && !part.isSmil) resolver.openOutputStream(partUri)?.use { it.write(part.data) }
        }

        fun addr(address: String, type: Int) = resolver.insert(
            Uri.parse("content://mms/$id/addr"),
            ContentValues().apply {
                put(Mms.Addr.ADDRESS, address)
                put(Mms.Addr.TYPE, type)
                put(Mms.Addr.CHARSET, CHARSET_UTF8)
            },
        )
        addr(from ?: INSERT_ADDRESS_TOKEN, ADDR_FROM)
        to.forEach { addr(it, ADDR_TO) }
        cc.forEach { addr(it, ADDR_CC) }
        return uri
    }

    /** Parts of a stored MMS, to send it again. */
    fun parts(context: Context, id: Long): List<PduPart> {
        val resolver = context.contentResolver
        val list = mutableListOf<PduPart>()
        resolver.query(
            Uri.parse("content://mms/$id/part"),
            arrayOf(Mms.Part._ID, Mms.Part.CONTENT_TYPE, Mms.Part.TEXT, Mms.Part.NAME, Mms.Part.CONTENT_ID, Mms.Part.CONTENT_LOCATION, Mms.Part.CHARSET),
            null, null, "${Mms.Part.SEQ} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(1) ?: continue
                val text = type == "text/plain" || type == "application/smil"
                val data = if (text) c.getString(2).orEmpty().toByteArray()
                else resolver.openInputStream(Uri.parse("content://mms/part/${c.getLong(0)}"))?.use { it.readBytes() } ?: continue
                list += PduPart(
                    type, data, name = c.getString(3), contentId = c.getString(4)?.removeSurrounding("<", ">"),
                    contentLocation = c.getString(5), charset = if (text) CHARSET_UTF8 else c.getInt(6).takeIf { it != 0 },
                )
            }
        }
        return list
    }

    fun addresses(context: Context, id: Long, type: Int): List<String> =
        context.contentResolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf(Mms.Addr.ADDRESS), "${Mms.Addr.TYPE} = $type", null, null,
        )?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.takeIf { it != INSERT_ADDRESS_TOKEN }?.let(::add) } }.orEmpty()

    fun update(context: Context, uri: Uri, values: ContentValues.() -> Unit) {
        context.contentResolver.update(uri, ContentValues().apply(values), null, null)
    }

    fun threadOf(context: Context, uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(Mms.THREAD_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
}

fun maxMmsSize(context: Context, subId: Int): Int = try {
    smsManager(context, subId).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 300 * 1024)
} catch (_: Exception) {
    300 * 1024
}.coerceIn(100 * 1024, 2 * 1024 * 1024)

object MmsSender {
    /** Saves an MMS to [recipients] in the outbox and sends it. */
    fun send(context: Context, recipients: List<String>, text: String, attachments: List<Uri>, subId: Int): Uri? {
        val parts = MmsParts.build(context, text, attachments, maxMmsSize(context, subId))
        if (parts.none { !it.isSmil }) return null
        val threadId = Threads.getOrCreateThreadId(context, recipients.toSet())
        val transactionId = "T" + java.lang.Long.toHexString(System.currentTimeMillis())
        val uri = MmsStore.insert(
            context, box = Mms.MESSAGE_BOX_OUTBOX, messageType = MessageType.SEND_REQ, threadId = threadId,
            from = null, to = recipients, parts = parts, read = true, transactionId = transactionId,
            size = parts.sumOf { it.data.size.toLong() }, subId = subId,
        ) ?: return null
        transmit(context, uri, recipients, parts, transactionId, subId)
        return uri
    }

    fun retry(context: Context, uri: Uri) {
        val id = ContentUris.parseId(uri)
        val (transactionId, subId) = context.contentResolver.query(
            uri, arrayOf(Mms.TRANSACTION_ID, Mms.SUBSCRIPTION_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) (c.getString(0) ?: "T$id") to c.getInt(1) else null } ?: return
        val recipients = MmsStore.addresses(context, id, ADDR_TO)
        MmsStore.update(context, uri) {
            put(Mms.MESSAGE_BOX, Mms.MESSAGE_BOX_OUTBOX)
            put(Mms.DATE, System.currentTimeMillis() / 1000)
        }
        transmit(context, uri, recipients, MmsStore.parts(context, id), transactionId, subId)
    }

    private fun transmit(context: Context, uri: Uri, recipients: List<String>, parts: List<PduPart>, transactionId: String, subId: Int) {
        try {
            val pdu = PduComposer.sendRequest(
                transactionId, recipients, parts, deliveryReport = MessagesApp.of(context).settings.deliveryReports,
            )
            val file = File(mmsDir(context), "send-${ContentUris.parseId(uri)}.pdu").apply { writeBytes(pdu) }
            val sent = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, MmsSentReceiver::class.java).setData(uri).putExtra(EXTRA_FILE, file.path),
                mutableFlags(),
            )
            smsManager(context, subId).sendMultimediaMessage(context, context.fileUri(file), null, null, sent)
        } catch (e: Exception) {
            MmsStore.update(context, uri) { put(Mms.MESSAGE_BOX, Mms.MESSAGE_BOX_FAILED) }
        }
    }

    const val EXTRA_FILE = "file"
}

class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val result = resultCode
        val pending = goAsync()
        MessagesApp.of(context).scope.launch {
            try {
                intent.getStringExtra(MmsSender.EXTRA_FILE)?.let { File(it).delete() }
                val conf = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.let(PduParser::parseOrNull)
                val ok = result == Activity.RESULT_OK &&
                    (conf?.responseStatus == null || conf.responseStatus == MmsStatus.RESPONSE_OK)
                MmsStore.update(context, uri) {
                    put(Mms.MESSAGE_BOX, if (ok) Mms.MESSAGE_BOX_SENT else Mms.MESSAGE_BOX_FAILED)
                    conf?.messageId?.let { put(Mms.MESSAGE_ID, it) }
                    conf?.responseStatus?.let { put(Mms.RESPONSE_STATUS, it) }
                }
                if (!ok) MmsStore.threadOf(context, uri)?.let { Notifications.showSendFailed(context, it) }
            } finally {
                pending.finish()
            }
        }
    }
}

object MmsDownloader {
    /** Downloads the message announced by a stored notification ([uri]). */
    fun download(context: Context, uri: Uri) {
        val row = context.contentResolver.query(
            uri, arrayOf(Mms.CONTENT_LOCATION, Mms.SUBSCRIPTION_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getInt(1) else null } ?: return
        val (location, subId) = row
        if (location.isNullOrBlank()) return
        MmsStore.update(context, uri) { put(Mms.STATUS, DownloadState.DOWNLOADING) }
        try {
            val file = File(mmsDir(context), "download-${ContentUris.parseId(uri)}.pdu").apply { delete(); createNewFile() }
            val done = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, MmsDownloadedReceiver::class.java).setData(uri).putExtra(MmsSender.EXTRA_FILE, file.path),
                mutableFlags(),
            )
            smsManager(context, subId).downloadMultimediaMessage(context, location, context.fileUri(file), null, done)
        } catch (e: Exception) {
            MmsStore.update(context, uri) { put(Mms.STATUS, DownloadState.FAILED) }
        }
    }
}

/** A finished download: stores the message and replaces the notification with it. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationUri = intent.data ?: return
        val result = resultCode
        val pending = goAsync()
        MessagesApp.of(context).scope.launch {
            try {
                val file = intent.getStringExtra(MmsSender.EXTRA_FILE)?.let(::File)
                val pdu = if (result == Activity.RESULT_OK) file?.takeIf { it.exists() }?.readBytes()?.let(PduParser::parseOrNull) else null
                file?.delete()
                if (pdu == null || pdu.type != MessageType.RETRIEVE_CONF) {
                    failed(context, notificationUri)
                } else {
                    stored(context, notificationUri, pdu)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun failed(context: Context, uri: Uri) {
        MmsStore.update(context, uri) {
            put(Mms.STATUS, DownloadState.FAILED)
            put(Mms.READ, 0)
            put(Mms.SEEN, 0)
        }
        MmsStore.threadOf(context, uri)?.let { Notifications.showThread(context, it) }
    }

    private fun stored(context: Context, notificationUri: Uri, pdu: Pdu) {
        val resolver = context.contentResolver
        val info = resolver.query(
            notificationUri, arrayOf(Mms.TRANSACTION_ID, Mms.SUBSCRIPTION_ID, Mms.THREAD_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getInt(1), c.getLong(2)) else null }
        val subId = info?.second ?: -1

        // The system may have stored it already; don't keep two copies.
        val existing = pdu.messageId?.let { id ->
            resolver.query(Mms.CONTENT_URI, arrayOf(Mms._ID), "${Mms.MESSAGE_ID} = ? AND ${Mms.MESSAGE_TYPE} = ${MessageType.RETRIEVE_CONF}", arrayOf(id), null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }
        val threadId = if (existing != null) {
            MmsStore.threadOf(context, ContentUris.withAppendedId(Mms.CONTENT_URI, existing))
        } else {
            val participants = participants(context, pdu)
            val threadId = if (participants.isEmpty()) info?.third ?: return else Threads.getOrCreateThreadId(context, participants.toSet())
            MmsStore.insert(
                context, box = Mms.MESSAGE_BOX_INBOX, messageType = MessageType.RETRIEVE_CONF, threadId = threadId,
                from = pdu.from, to = pdu.to, cc = pdu.cc, parts = pdu.parts, subject = pdu.subject,
                dateSeconds = System.currentTimeMillis() / 1000, dateSentSeconds = pdu.date, read = false,
                transactionId = pdu.transactionId ?: info?.first, messageId = pdu.messageId,
                size = pdu.parts.sumOf { it.data.size.toLong() }, subId = subId,
            )
            threadId
        }
        resolver.delete(notificationUri, null, null)

        // Tell the MMSC we have it, so it doesn't send the notification again.
        (info?.first ?: pdu.transactionId)?.let { tid ->
            try {
                val file = File(mmsDir(context), "notifyresp-$tid.pdu".replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    .apply { writeBytes(PduComposer.notifyResponse(tid)) }
                smsManager(context, subId).sendMultimediaMessage(context, context.fileUri(file), null, null, null)
            } catch (_: Exception) {}
        }
        threadId?.let { Notifications.showThread(context, it) }
    }

    /** Everyone in the conversation but us: the sender and the other recipients. */
    private fun participants(context: Context, pdu: Pdu): List<String> {
        val own = MessagesApp.of(context).contacts.ownNumbers()
        val others = (pdu.to + pdu.cc).filter { address -> own.none { sameAddress(it, address) } }
        // If we don't know our own number, a message to a single recipient was to us.
        val rest = if (own.isEmpty() && pdu.to.size + pdu.cc.size <= 1) emptyList() else others
        return (listOfNotNull(pdu.from) + rest).distinctBy { it.filter(Char::isLetterOrDigit).takeLast(9) }
    }
}

/** WAP push with an MMS notification or delivery report (delivered only to the default SMS app). */
class MmsPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))
        val pending = goAsync()
        MessagesApp.of(context).scope.launch {
            try {
                val pdu = PduParser.parseOrNull(data) ?: return@launch
                when (pdu.type) {
                    MessageType.NOTIFICATION_IND -> onNotification(context, pdu, subId)
                    MessageType.DELIVERY_IND -> onDeliveryReport(context, pdu)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun onNotification(context: Context, pdu: Pdu, subId: Int) {
        val location = pdu.contentLocation ?: return
        val resolver = context.contentResolver
        // Carriers sometimes send the same notification twice.
        val duplicate = resolver.query(Mms.CONTENT_URI, arrayOf(Mms._ID), "${Mms.CONTENT_LOCATION} = ?", arrayOf(location), null)
            ?.use { it.count > 0 } == true
        if (duplicate) return

        val from = pdu.from ?: return
        val autoDownload = MessagesApp.of(context).settings.autoDownloadMms
        val expiry = pdu.expiry?.let { if (pdu.expiryRelative) System.currentTimeMillis() / 1000 + it else it }
        val uri = MmsStore.insert(
            context, box = Mms.MESSAGE_BOX_INBOX, messageType = MessageType.NOTIFICATION_IND,
            threadId = Threads.getOrCreateThreadId(context, from), from = from, to = emptyList(),
            subject = pdu.subject, read = autoDownload, transactionId = pdu.transactionId,
            contentLocation = location, size = pdu.messageSize, expiry = expiry, subId = subId,
            status = DownloadState.IDLE,
        ) ?: return
        if (autoDownload) {
            MmsDownloader.download(context, uri)
        } else {
            MmsStore.threadOf(context, uri)?.let { Notifications.showThread(context, it) }
        }
    }

    private fun onDeliveryReport(context: Context, pdu: Pdu) {
        val id = pdu.messageId ?: return
        if (pdu.status != MmsStatus.RETRIEVED) return
        context.contentResolver.update(
            Mms.CONTENT_URI,
            ContentValues().apply { put(Mms.STATUS, MMS_STATUS_RETRIEVED) },
            "${Mms.MESSAGE_ID} = ? AND ${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_SENT}", arrayOf(id),
        )
    }
}
