package dev.hostyn.messages.otp

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import dev.hostyn.messages.MessagesApp
import dev.hostyn.messages.R
import dev.hostyn.messages.notify.Notifications
import kotlinx.coroutines.launch

/** Puts a verification code on the clipboard, marked as sensitive so it isn't shown in previews. */
fun copyCode(context: Context, code: String) {
    val clip = ClipData.newPlainText(context.getString(R.string.verification_code), code)
    if (Build.VERSION.SDK_INT >= 24) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    // Android 13+ confirms copies itself.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.code_copied, Toast.LENGTH_SHORT).show()
}

/** "Copy 123456" from the notification: copies the code, then the message is done with. */
class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        val threadId = intent.getLongExtra(Notifications.EXTRA_THREAD, -1)
        copyCode(context, code)
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

    companion object {
        const val EXTRA_CODE = "code"
    }
}
