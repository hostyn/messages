package dev.hostyn.messages.sms

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dev.hostyn.messages.MessagesApp
import dev.hostyn.messages.notify.Notifications
import kotlinx.coroutines.launch

/** SMS manager for a SIM, or the default one. */
@Suppress("DEPRECATION")
fun smsManager(context: Context, subId: Int): SmsManager = when {
    Build.VERSION.SDK_INT >= 31 -> context.getSystemService(SmsManager::class.java).let {
        if (subId >= 0) it.createForSubscriptionId(subId) else it
    }
    subId >= 0 -> SmsManager.getSmsManagerForSubscriptionId(subId)
    else -> SmsManager.getDefault()
}

fun defaultSubId(): Int = SmsManager.getDefaultSmsSubscriptionId()

object SmsSender {
    const val ACTION_SENT = "dev.hostyn.messages.SMS_SENT"
    const val ACTION_DELIVERED = "dev.hostyn.messages.SMS_DELIVERED"
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"

    /** Saves the message in the outbox and sends it. Returns its URI. */
    fun send(context: Context, address: String, body: String, subId: Int): Uri? {
        val values = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            put(Sms.THREAD_ID, Threads.getOrCreateThreadId(context, address))
            put(Sms.SUBSCRIPTION_ID, subId)
        }
        val uri = context.contentResolver.insert(Sms.CONTENT_URI, values) ?: return null
        transmit(context, uri, address, body, subId)
        return uri
    }

    /** Sends a message that failed again. */
    fun retry(context: Context, uri: Uri) {
        val (address, body, subId) = context.contentResolver.query(
            uri, arrayOf(Sms.ADDRESS, Sms.BODY, Sms.SUBSCRIPTION_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1).orEmpty(), c.getInt(2)) else null } ?: return
        context.contentResolver.update(uri, ContentValues().apply {
            put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.ERROR_CODE, 0)
        }, null, null)
        transmit(context, uri, address, body, subId)
    }

    private fun transmit(context: Context, uri: Uri, address: String, body: String, subId: Int) {
        try {
            val manager = smsManager(context, subId)
            val parts = manager.divideMessage(body)
            val reports = MessagesApp.of(context).settings.deliveryReports
            val sent = ArrayList<PendingIntent>()
            val delivered = ArrayList<PendingIntent?>()
            parts.indices.forEach { i ->
                sent += statusIntent(context, ACTION_SENT, uri, i, parts.size)
                delivered += if (reports && i == parts.lastIndex) statusIntent(context, ACTION_DELIVERED, uri, i, parts.size) else null
            }
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
            } else {
                manager.sendTextMessage(address, null, body, sent[0], delivered[0])
            }
        } catch (e: Exception) {
            markFailed(context, uri, -1)
        }
    }

    private fun statusIntent(context: Context, action: String, uri: Uri, part: Int, parts: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            // Unique per message and part so the PendingIntents don't replace each other.
            .setData(uri.buildUpon().fragment("$action/$part").build())
            .putExtra(EXTRA_PART, part)
            .putExtra(EXTRA_PARTS, parts)
        // Mutable: the system adds the delivery report PDU and error code to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun markFailed(context: Context, uri: Uri, errorCode: Int) {
        context.contentResolver.update(uri, ContentValues().apply {
            put(Sms.TYPE, Sms.MESSAGE_TYPE_FAILED)
            put(Sms.ERROR_CODE, errorCode)
        }, null, null)
    }
}

/** Results of sending (each part) and delivery reports. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.data ?: return
        val uri = data.buildUpon().fragment(null).build()
        val result = resultCode
        val pending = goAsync()
        MessagesApp.of(context).scope.launch {
            try {
                when (intent.action) {
                    SmsSender.ACTION_SENT -> onSent(context, uri, intent, result)
                    SmsSender.ACTION_DELIVERED -> onDelivered(context, uri, intent)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun onSent(context: Context, uri: Uri, intent: Intent, result: Int) {
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
        val parts = intent.getIntExtra(SmsSender.EXTRA_PARTS, 1)
        if (result != Activity.RESULT_OK) {
            SmsSender.markFailed(context, uri, intent.getIntExtra("errorCode", result))
            threadOf(context, uri)?.let { Notifications.showSendFailed(context, it) }
        } else if (part == parts - 1) {
            // Don't overwrite a failure reported by an earlier part.
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(Sms.TYPE, Sms.MESSAGE_TYPE_SENT) },
                "${Sms.TYPE} != ${Sms.MESSAGE_TYPE_FAILED}", null,
            )
        }
    }

    private fun onDelivered(context: Context, uri: Uri, intent: Intent) {
        val pdu = intent.getByteArrayExtra("pdu") ?: return
        val message = SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")) ?: return
        val status = message.status
        val value = when {
            status == 0 -> Sms.STATUS_COMPLETE
            status >= 0x40 -> Sms.STATUS_FAILED
            else -> Sms.STATUS_PENDING
        }
        context.contentResolver.update(uri, ContentValues().apply { put(Sms.STATUS, value) }, null, null)
    }

    private fun threadOf(context: Context, uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(Sms.THREAD_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
}

/** Incoming SMS, delivered only to the default SMS app, which has to store them. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))
        val pending = goAsync()
        MessagesApp.of(context).scope.launch {
            try {
                val first = parts.first()
                val address = first.displayOriginatingAddress ?: first.originatingAddress ?: return@launch
                val values = ContentValues().apply {
                    put(Sms.ADDRESS, address)
                    put(Sms.BODY, parts.joinToString("") { it.displayMessageBody.orEmpty() })
                    put(Sms.DATE, System.currentTimeMillis())
                    put(Sms.DATE_SENT, first.timestampMillis)
                    put(Sms.READ, 0)
                    put(Sms.SEEN, 0)
                    put(Sms.TYPE, Sms.MESSAGE_TYPE_INBOX)
                    put(Sms.SUBSCRIPTION_ID, subId)
                    put(Sms.SERVICE_CENTER, first.serviceCenterAddress)
                    put(Sms.PROTOCOL, first.protocolIdentifier)
                    put(Sms.REPLY_PATH_PRESENT, if (first.isReplyPathPresent) 1 else 0)
                }
                val uri = context.contentResolver.insert(Sms.Inbox.CONTENT_URI, values) ?: return@launch
                val threadId = context.contentResolver.query(uri, arrayOf(Sms.THREAD_ID), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@launch
                Notifications.showThread(context, threadId)
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Reply with a message" when declining a call: sends the text without opening the app. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val recipients = recipientsOf(intent.data)
            val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, defaultSubId())
            if (!text.isNullOrBlank() && recipients.isNotEmpty()) {
                MessagesApp.of(this).scope.launch {
                    recipients.forEach { SmsSender.send(this@HeadlessSmsSendService, it, text, subId) }
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** Addresses in an sms:, smsto:, mms: or mmsto: URI ("smsto:600111222;600333444"). */
fun recipientsOf(uri: Uri?): List<String> {
    if (uri == null) return emptyList()
    val part = uri.schemeSpecificPart?.substringBefore('?').orEmpty()
    return part.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
}
