package dev.hostyn.messages.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony.Mms
import android.provider.Telephony.MmsSms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import dev.hostyn.messages.R
import dev.hostyn.messages.mms.DownloadState
import dev.hostyn.messages.mms.MessageType
import dev.hostyn.messages.mms.charsetFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Reads and changes conversations and messages in the system Telephony provider. */
class MessagesRepository(private val context: Context, val contacts: Contacts) {
    private val resolver = context.contentResolver

    /** Emits once now and again (debounced) whenever messages or contacts change. */
    @OptIn(FlowPreview::class)
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        listOf(MmsSms.CONTENT_URI, Sms.CONTENT_URI, Mms.CONTENT_URI, ContactsContract.Contacts.CONTENT_URI)
            .forEach { resolver.registerContentObserver(it, true, observer) }
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.buffer(Channel.CONFLATED).debounce(150)

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The conversation list, kept for a minute after the list screen goes away: coming back from a
     * conversation shows it at once, so the conversation can shrink back into its row.
     */
    val conversations: StateFlow<List<Conversation>?> =
        changes().map { queryConversations() }.conflate().flowOn(Dispatchers.IO)
            .stateIn(cacheScope, SharingStarted.WhileSubscribed(60_000), null)

    fun messages(threadId: Long): Flow<List<Message>> =
        changes().map { queryMessages(threadId) }.conflate().flowOn(Dispatchers.IO)

    fun recipientsFlow(threadId: Long): Flow<List<Recipient>> =
        changes().map { threadRecipients(threadId) }.flowOn(Dispatchers.IO)

    // --- Conversations ---

    private fun canonicalAddresses(): Map<Long, String> {
        val map = HashMap<Long, String>()
        resolver.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)
            ?.use { c -> while (c.moveToNext()) map[c.getLong(0)] = c.getString(1).orEmpty() }
        return map
    }

    private val conversationsUri = Uri.parse("content://mms-sms/conversations?simple=true")

    /** Newest failed message per thread (milliseconds), to flag threads whose last message wasn't sent. */
    private fun lastFailures(): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        resolver.query(Sms.CONTENT_URI, arrayOf(Sms.THREAD_ID, Sms.DATE), "${Sms.TYPE} = ${Sms.MESSAGE_TYPE_FAILED}", null, null)
            ?.use { c -> while (c.moveToNext()) out.merge(c.getLong(0), c.getLong(1), ::maxOf) }
        resolver.query(Mms.CONTENT_URI, arrayOf(Mms.THREAD_ID, Mms.DATE), "${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_FAILED}", null, null)
            ?.use { c -> while (c.moveToNext()) out.merge(c.getLong(0), c.getLong(1) * 1000, ::maxOf) }
        return out
    }

    fun queryConversations(): List<Conversation> {
        val addresses = canonicalAddresses()
        val failures = lastFailures()
        val result = mutableListOf<Conversation>()
        resolver.query(
            conversationsUri,
            arrayOf(Threads._ID, Threads.DATE, Threads.MESSAGE_COUNT, Threads.RECIPIENT_IDS, Threads.SNIPPET,
                Threads.SNIPPET_CHARSET, Threads.READ, Threads.ERROR, Threads.HAS_ATTACHMENT),
            null, null, "${Threads.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) == 0) continue
                val threadId = c.getLong(0)
                val recipients = recipientsOf(c.getString(3), addresses)
                var snippet = decodeMmsString(c.getString(4), c.getInt(5))
                if (snippet.isBlank()) snippet = lastMmsText(threadId)
                result += Conversation(
                    threadId = threadId,
                    recipients = recipients,
                    snippet = snippet,
                    date = c.getLong(1),
                    unread = c.getInt(6) == 0,
                    hasAttachment = c.getInt(8) == 1,
                    hasError = c.getInt(7) != 0 || (failures[threadId] ?: -1) >= c.getLong(1),
                )
            }
        }
        return result
    }

    private fun recipientsOf(ids: String?, addresses: Map<Long, String>): List<Recipient> =
        ids.orEmpty().split(' ').mapNotNull { it.toLongOrNull()?.let(addresses::get) }
            .filter { it.isNotBlank() }
            .map(contacts::recipient)

    fun threadRecipients(threadId: Long): List<Recipient> {
        val ids = resolver.query(conversationsUri, arrayOf(Threads.RECIPIENT_IDS), "${Threads._ID} = ?", arrayOf("$threadId"), null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return recipientsOf(ids, canonicalAddresses())
    }

    /** Thread for these addresses, created if needed. */
    fun threadIdFor(addresses: Collection<String>): Long = Threads.getOrCreateThreadId(context, addresses.toSet())

    /** Text of the newest MMS in a thread (the provider's snippet is only the MMS subject), or what it carries. */
    private fun lastMmsText(threadId: Long): String {
        val id = resolver.query(Mms.CONTENT_URI, arrayOf(Mms._ID), "${Mms.THREAD_ID} = ?", arrayOf("$threadId"), "${Mms.DATE} DESC")
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return ""
        val texts = mutableListOf<String>()
        val types = mutableListOf<String>()
        resolver.query(Uri.parse("content://mms/$id/part"), arrayOf(Mms.Part.CONTENT_TYPE, Mms.Part.TEXT), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0).orEmpty().lowercase()
                when {
                    type == "text/plain" -> c.getString(1)?.takeIf { it.isNotBlank() }?.let(texts::add)
                    type != "application/smil" -> types += type
                }
            }
        }
        if (texts.isNotEmpty()) return texts.joinToString(" ")
        val type = types.firstOrNull() ?: return ""
        return context.getString(
            when {
                type.startsWith("image/") -> R.string.attachment_photo
                type.startsWith("video/") -> R.string.attachment_video
                type.startsWith("audio/") -> R.string.attachment_audio
                "vcard" in type -> R.string.attachment_contact
                else -> R.string.attachment_file
            },
        )
    }

    // --- Messages ---

    fun queryMessages(threadId: Long, limit: Int = 1000): List<Message> {
        val list = ArrayList<Message>()
        resolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.STATUS, Sms.SUBSCRIPTION_ID, Sms.READ),
            "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", arrayOf("$threadId"), "${Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && c.position < limit) {
                val type = c.getInt(4)
                val status = when (type) {
                    Sms.MESSAGE_TYPE_INBOX -> MessageStatus.Received
                    Sms.MESSAGE_TYPE_SENT -> if (c.getInt(5) == Sms.STATUS_COMPLETE) MessageStatus.Delivered else MessageStatus.Sent
                    Sms.MESSAGE_TYPE_FAILED -> MessageStatus.Failed
                    else -> MessageStatus.Sending
                }
                list += Message(
                    id = c.getLong(0), isMms = false, threadId = threadId,
                    sender = if (status == MessageStatus.Received) c.getString(1) else null,
                    body = c.getString(2).orEmpty(), subject = null, date = c.getLong(3), status = status,
                    subId = c.getInt(6), read = c.getInt(7) == 1,
                )
            }
        }
        list += queryMms(threadId, limit)
        list.sortBy { it.date }
        return if (list.size > limit) list.subList(list.size - limit, list.size) else list
    }

    private fun queryMms(threadId: Long, limit: Int): List<Message> {
        data class Row(
            val id: Long, val date: Long, val box: Int, val subject: String?, val type: Int, val location: String?,
            val size: Long, val subId: Int, val read: Boolean, val status: Int,
        )
        val rows = ArrayList<Row>()
        resolver.query(
            Mms.CONTENT_URI,
            arrayOf(Mms._ID, Mms.DATE, Mms.MESSAGE_BOX, Mms.SUBJECT, Mms.SUBJECT_CHARSET, Mms.MESSAGE_TYPE,
                Mms.CONTENT_LOCATION, Mms.MESSAGE_SIZE, Mms.SUBSCRIPTION_ID, Mms.READ, Mms.STATUS),
            "${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} != ${Mms.MESSAGE_BOX_DRAFTS}", arrayOf("$threadId"), "${Mms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && c.position < limit) {
                rows += Row(
                    c.getLong(0), c.getLong(1) * 1000, c.getInt(2),
                    c.getString(3)?.let { decodeMmsString(it, c.getInt(4)) }?.takeIf { it.isNotBlank() },
                    c.getInt(5), c.getString(6), c.getLong(7), c.getInt(8), c.getInt(9) == 1, c.getInt(10),
                )
            }
        }
        if (rows.isEmpty()) return emptyList()

        // Parts of all these messages in one query.
        val texts = HashMap<Long, StringBuilder>()
        val attachments = HashMap<Long, MutableList<Attachment>>()
        rows.map { it.id }.chunked(500).forEach { ids ->
            resolver.query(
                Uri.parse("content://mms/part"),
                arrayOf(Mms.Part._ID, Mms.Part.MSG_ID, Mms.Part.CONTENT_TYPE, Mms.Part.TEXT, Mms.Part.NAME, Mms.Part.CONTENT_LOCATION),
                "${Mms.Part.MSG_ID} IN (${ids.joinToString(",")})", null, "${Mms.Part.SEQ} ASC, ${Mms.Part._ID} ASC",
            )?.use { c -> while (c.moveToNext()) readPart(c, texts, attachments) }
        }

        val recipients = threadRecipients(threadId)
        val isGroup = recipients.size > 1
        return rows.map { r ->
            val incoming = r.box == Mms.MESSAGE_BOX_INBOX
            val status = when (r.box) {
                Mms.MESSAGE_BOX_INBOX -> MessageStatus.Received
                Mms.MESSAGE_BOX_SENT -> if (r.status == MMS_STATUS_RETRIEVED) MessageStatus.Delivered else MessageStatus.Sent
                Mms.MESSAGE_BOX_FAILED -> MessageStatus.Failed
                else -> MessageStatus.Sending
            }
            Message(
                id = r.id, isMms = true, threadId = threadId,
                sender = if (incoming) (if (isGroup) mmsSender(r.id) else null) ?: recipients.firstOrNull()?.address else null,
                body = texts[r.id]?.toString()?.trim().orEmpty(),
                subject = r.subject,
                date = r.date, status = status,
                attachments = attachments[r.id].orEmpty(),
                subId = r.subId,
                pendingDownload = r.type == MessageType.NOTIFICATION_IND,
                downloading = r.type == MessageType.NOTIFICATION_IND && r.status == DownloadState.DOWNLOADING,
                downloadUrl = r.location,
                sizeBytes = r.size,
                read = r.read,
            )
        }
    }

    private fun readPart(c: Cursor, texts: HashMap<Long, StringBuilder>, attachments: HashMap<Long, MutableList<Attachment>>) {
        val partId = c.getLong(0)
        val mid = c.getLong(1)
        val type = c.getString(2).orEmpty().lowercase()
        when {
            type == "application/smil" -> Unit
            type == "text/plain" -> texts.getOrPut(mid) { StringBuilder() }.apply {
                if (isNotEmpty()) append('\n')
                append(c.getString(3).orEmpty())
            }
            else -> attachments.getOrPut(mid) { mutableListOf() } +=
                Attachment(partId, Uri.parse("content://mms/part/$partId"), type, c.getString(4) ?: c.getString(5))
        }
    }

    /** Sender (the From address) of an incoming MMS. */
    fun mmsSender(id: Long): String? = resolver.query(
        Uri.parse("content://mms/$id/addr"), arrayOf(Mms.Addr.ADDRESS), "${Mms.Addr.TYPE} = $ADDR_FROM", null, null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it != INSERT_ADDRESS_TOKEN } else null }

    /** Unread incoming messages of a thread, oldest first (for notifications). */
    fun unreadMessages(threadId: Long): List<Message> = queryMessages(threadId, limit = 50).filter { it.incoming && !it.read }

    /** Threads that have unread messages. */
    fun unreadThreads(): List<Long> = queryConversations().filter { it.unread }.map { it.threadId }

    // --- Changes ---

    fun markRead(threadId: Long) {
        val values = ContentValues().apply {
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
        }
        val where = "${Sms.THREAD_ID} = ? AND (${Sms.READ} = 0 OR ${Sms.SEEN} = 0)"
        try {
            resolver.update(Sms.CONTENT_URI, values, where, arrayOf("$threadId"))
            resolver.update(Mms.CONTENT_URI, values, where, arrayOf("$threadId"))
        } catch (_: Exception) {
            // Not the default SMS app.
        }
    }

    fun markUnread(threadId: Long) {
        // Marking the newest incoming message is enough for the thread to show as unread.
        val last = queryMessages(threadId, limit = 20).lastOrNull { it.incoming } ?: return
        val values = ContentValues().apply { put(Sms.READ, 0) }
        try {
            resolver.update(messageUri(last), values, null, null)
        } catch (_: Exception) {}
    }

    fun deleteThreads(threadIds: Collection<Long>) {
        threadIds.forEach { resolver.delete(ContentUris.withAppendedId(Threads.CONTENT_URI, it), null, null) }
    }

    fun deleteMessage(message: Message) {
        resolver.delete(messageUri(message), null, null)
    }

    fun messageUri(message: Message): Uri =
        ContentUris.withAppendedId(if (message.isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, message.id)

    companion object {
        const val ADDR_FROM = 137
        const val ADDR_TO = 151
        const val ADDR_CC = 130
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"
        /** Mms.STATUS value we store when a delivery report says the message arrived. */
        const val MMS_STATUS_RETRIEVED = 129
    }
}

/**
 * MMS subjects (and thread snippets taken from them) are stored as their raw bytes read as
 * ISO-8859-1, with the real charset in a separate column.
 */
fun decodeMmsString(value: String?, charset: Int): String {
    if (value == null) return ""
    if (charset == 0 || value.any { it.code > 0xFF }) return value
    return try {
        String(value.toByteArray(Charsets.ISO_8859_1), charsetFor(charset))
    } catch (_: Exception) {
        value
    }
}

fun encodeMmsString(value: String): String = String(value.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
