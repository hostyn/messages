package dev.hostyn.messages.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import dev.hostyn.messages.MainActivity
import dev.hostyn.messages.MessagesApp
import dev.hostyn.messages.R
import dev.hostyn.messages.otp.CopyCodeReceiver
import dev.hostyn.messages.otp.OtpDetector
import dev.hostyn.messages.data.Message
import dev.hostyn.messages.data.Recipient
import dev.hostyn.messages.mms.MmsSender
import dev.hostyn.messages.sms.SmsSender
import dev.hostyn.messages.sms.defaultSubId
import dev.hostyn.messages.ui.components.attachmentLabel
import kotlinx.coroutines.launch

object Notifications {
    private const val CHANNEL = "messages"
    private const val CHANNEL_FAILED = "failed"
    const val KEY_REPLY = "reply"
    const val EXTRA_THREAD = "thread_id"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FAILED, context.getString(R.string.channel_failed), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openThread(context: Context, threadId: Long): PendingIntent = PendingIntent.getActivity(
        context, threadId.toInt(),
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_THREAD, threadId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(context: Context, cls: Class<*>, threadId: Long, mutable: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            context, threadId.toInt(),
            Intent(context, cls).setData(Uri.parse("thread:$threadId")).putExtra(EXTRA_THREAD, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else if (mutable) 0 else PendingIntent.FLAG_IMMUTABLE,
        )

    /** Shows (or updates, or removes) the notification for a conversation's unread messages. */
    fun showThread(context: Context, threadId: Long) {
        val app = MessagesApp.of(context)
        if (app.openThreadId == threadId) {
            // The conversation is on screen: it's read already.
            app.repository.markRead(threadId)
            return
        }
        val unread = app.repository.unreadMessages(threadId)
        val nm = NotificationManagerCompat.from(context)
        if (unread.isEmpty()) {
            nm.cancel(threadId.toInt())
            return
        }
        if (!canNotify(context)) return

        val recipients = app.repository.threadRecipients(threadId)
        val isGroup = recipients.size > 1
        val me = Person.Builder().setName(context.getString(R.string.you)).build()
        val people = HashMap<String, Person>()
        fun person(address: String?): Person {
            val recipient = recipients.firstOrNull { it.address == address } ?: app.contacts.recipient(address.orEmpty())
            return people.getOrPut(recipient.address) {
                Person.Builder()
                    .setName(recipient.displayName)
                    .setKey(recipient.address)
                    .setIcon(IconCompat.createWithBitmap(avatar(context, recipient)))
                    .build()
            }
        }

        val style = NotificationCompat.MessagingStyle(me)
        if (isGroup) {
            style.isGroupConversation = true
            style.conversationTitle = recipients.joinToString(", ") { it.displayName }
        }
        unread.takeLast(8).forEach { m -> style.addMessage(notificationText(context, m), m.date, person(m.sender)) }

        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_reply, context.getString(R.string.reply),
            action(context, ReplyReceiver::class.java, threadId, mutable = true),
        )
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.reply)).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_done, context.getString(R.string.mark_read),
            action(context, MarkReadReceiver::class.java, threadId),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        // Verification code in the newest message: offer to copy it right from the notification.
        val code = unread.lastOrNull { it.body.isNotBlank() }?.body?.let(OtpDetector::find)
        val copy = code?.let {
            NotificationCompat.Action.Builder(
                R.drawable.ic_stat_copy, context.getString(R.string.copy_code_value, it),
                PendingIntent.getBroadcast(
                    context, threadId.toInt(),
                    Intent(context, CopyCodeReceiver::class.java)
                        .setData(Uri.parse("thread:$threadId"))
                        .putExtra(EXTRA_THREAD, threadId)
                        .putExtra(CopyCodeReceiver.EXTRA_CODE, it),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
                .setShowsUserInterface(false)
                .build()
        }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setStyle(style)
            .setContentIntent(openThread(context, threadId))
            .setDeleteIntent(null)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setWhen(unread.last().date)
            .setNumber(unread.size)
            .apply { copy?.let(::addAction) }
            .addAction(reply)
            .addAction(markRead)
            .build()
        try {
            nm.notify(threadId.toInt(), notification)
        } catch (_: SecurityException) {}
    }

    fun showSendFailed(context: Context, threadId: Long) {
        if (!canNotify(context) || MessagesApp.of(context).openThreadId == threadId) return
        val recipients = MessagesApp.of(context).repository.threadRecipients(threadId)
        val notification = NotificationCompat.Builder(context, CHANNEL_FAILED)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle(context.getString(R.string.not_sent_title))
            .setContentText(context.getString(R.string.not_sent_body, recipients.joinToString(", ") { it.displayName }))
            .setContentIntent(openThread(context, threadId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(-threadId.toInt() - 1, notification)
        } catch (_: SecurityException) {}
    }

    fun cancel(context: Context, threadId: Long) {
        NotificationManagerCompat.from(context).cancel(threadId.toInt())
        NotificationManagerCompat.from(context).cancel(-threadId.toInt() - 1)
    }

    private fun notificationText(context: Context, m: Message): CharSequence = when {
        m.pendingDownload -> context.getString(R.string.mms_pending)
        m.body.isNotBlank() -> m.body
        m.attachments.isNotEmpty() -> attachmentLabel(context, m.attachments.first())
        else -> m.subject.orEmpty()
    }

    /** Contact photo, or a letter on a colored circle. */
    private fun avatar(context: Context, recipient: Recipient): Bitmap {
        val size = (64 * context.resources.displayMetrics.density).toInt()
        recipient.photoUri?.let { uri ->
            try {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)?.let { return circle(it, size) }
                }
            } catch (_: Exception) {}
        }
        val colors = listOf(0xFFD9E2FF to 0xFF001944, 0xFFDBE2F9 to 0xFF141B2C, 0xFFFBD7FC to 0xFF29132D)
        val (bg, fg) = colors[Math.floorMod(recipient.seed, colors.size.toLong()).toInt()]
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = bg.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val letter = recipient.name?.trim()?.firstOrNull { it.isLetter() }?.uppercase() ?: "#"
        paint.color = fg.toInt()
        paint.textSize = size * 0.45f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }

    private fun circle(source: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val scaled = Bitmap.createScaledBitmap(source, size, size, true)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return out
    }
}

/** "Reply" from the notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(Notifications.EXTRA_THREAD, -1)
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_REPLY)?.toString()
        if (threadId < 0 || text.isNullOrBlank()) return
        val app = MessagesApp.of(context)
        val pending = goAsync()
        app.scope.launch {
            try {
                val recipients = app.repository.threadRecipients(threadId).map { it.address }
                val subId = app.repository.queryMessages(threadId, limit = 1).lastOrNull()?.subId?.takeIf { it >= 0 } ?: defaultSubId()
                when {
                    recipients.size == 1 -> SmsSender.send(context, recipients.first(), text, subId)
                    recipients.size > 1 -> MmsSender.send(context, recipients, text, emptyList(), subId)
                }
                app.repository.markRead(threadId)
                Notifications.cancel(context, threadId)
            } finally {
                pending.finish()
            }
        }
    }
}

class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(Notifications.EXTRA_THREAD, -1)
        if (threadId < 0) return
        val app = MessagesApp.of(context)
        val pending = goAsync()
        app.scope.launch {
            try {
                app.repository.markRead(threadId)
                Notifications.cancel(context, threadId)
            } finally {
                pending.finish()
            }
        }
    }
}
