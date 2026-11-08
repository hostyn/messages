package dev.hostyn.messages

import android.app.Application
import android.content.Context
import dev.hostyn.messages.data.AppSettings
import dev.hostyn.messages.data.Contacts
import dev.hostyn.messages.data.MessagesRepository
import dev.hostyn.messages.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MessagesApp : Application() {
    val settings by lazy { AppSettings(this) }
    val contacts by lazy { Contacts(this) }
    val repository by lazy { MessagesRepository(this, contacts) }

    /** For work started by receivers and the UI that must outlive a screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Conversation currently on screen, so its messages don't raise notifications. */
    @Volatile
    var openThreadId: Long? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }

    companion object {
        fun of(context: Context) = context.applicationContext as MessagesApp
    }
}
