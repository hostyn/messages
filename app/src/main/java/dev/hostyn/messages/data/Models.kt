package dev.hostyn.messages.data

import android.net.Uri

/** Someone in a conversation, resolved against the contacts when possible. */
data class Recipient(
    val address: String,
    val name: String? = null,
    val photoUri: String? = null,
    val contactId: Long? = null,
) {
    val displayName get() = name ?: address
    /** Stable seed for the avatar color. */
    val seed get() = contactId ?: address.filter { it.isDigit() }.takeLast(9).toLongOrNull() ?: address.hashCode().toLong()
}

data class Conversation(
    val threadId: Long,
    val recipients: List<Recipient>,
    val snippet: String,
    /** Milliseconds. */
    val date: Long,
    val unread: Boolean,
    val hasAttachment: Boolean,
    val hasError: Boolean,
) {
    val isGroup get() = recipients.size > 1
    val title get() = recipients.joinToString(", ") { it.displayName }
}

enum class MessageStatus { Received, Sending, Sent, Delivered, Failed }

data class Attachment(
    val partId: Long,
    val uri: Uri,
    val contentType: String,
    val name: String?,
) {
    val isImage get() = contentType.startsWith("image/")
    val isVideo get() = contentType.startsWith("video/")
    val isAudio get() = contentType.startsWith("audio/")
    val isVCard get() = contentType.contains("vcard", ignoreCase = true)
}

data class Message(
    val id: Long,
    val isMms: Boolean,
    val threadId: Long,
    /** Sender for incoming messages; null for our own. */
    val sender: String?,
    val body: String,
    val subject: String?,
    /** Milliseconds. */
    val date: Long,
    val status: MessageStatus,
    val attachments: List<Attachment> = emptyList(),
    val subId: Int = -1,
    /** MMS that still has to be downloaded (a notification); [downloadUrl] is set. */
    val pendingDownload: Boolean = false,
    val downloadUrl: String? = null,
    val downloading: Boolean = false,
    val sizeBytes: Long = 0,
    val read: Boolean = true,
) {
    val incoming get() = status == MessageStatus.Received
    /** Unique across SMS and MMS. */
    val key get() = if (isMms) -id else id
}

/** A contact's phone number, for starting a new conversation. */
data class ContactPhone(
    val contactId: Long,
    val name: String,
    val number: String,
    val label: String?,
    val photoUri: String?,
)
