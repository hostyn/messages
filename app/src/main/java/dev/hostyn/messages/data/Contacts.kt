package dev.hostyn.messages.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Resolves addresses to contacts (cached) and lists contacts' phone numbers. */
class Contacts(private val context: Context) {
    private val cache = ConcurrentHashMap<String, Recipient>()

    init {
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = cache.clear()
            },
        )
    }

    private val canRead get() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun recipient(address: String): Recipient = cache.getOrPut(address) { lookup(address) }

    private fun lookup(address: String): Recipient {
        // Short codes and alphanumeric senders ("BANK") aren't in the contacts.
        if (!canRead || address.isBlank() || address.none { it.isDigit() } && '@' !in address) return Recipient(address)
        val uri = if ('@' in address) {
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(address))
        } else {
            Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        }
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(PhoneLookup._ID, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) Recipient(address, c.getString(1), c.getString(2), c.getLong(0)) else null
            } ?: Recipient(address)
        } catch (_: Exception) {
            Recipient(address)
        }
    }

    /** Every phone number in the contacts, sorted by name. */
    fun phones(): List<ContactPhone> {
        if (!canRead) return emptyList()
        val out = mutableListOf<ContactPhone>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI),
            null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { c ->
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                val number = c.getString(2) ?: continue
                val contactId = c.getLong(0)
                // The same number is often stored twice (e.g. "+34 600…" and "600…") in linked contacts.
                if (!seen.add("$contactId:${PhoneNumberUtils.normalizeNumber(number).takeLast(9)}")) continue
                val label = Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                out += ContactPhone(contactId, c.getString(1) ?: number, number, label, c.getString(5))
            }
        }
        return out
    }

    /** Our own phone numbers, to leave ourselves out of group conversations. */
    @SuppressLint("MissingPermission")
    fun ownNumbers(): List<String> = try {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        sm.activeSubscriptionInfoList.orEmpty().mapNotNull { info ->
            val number = if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId) else @Suppress("DEPRECATION") info.number
            number?.takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Same phone number in different formats ("+34 600 11 22 33" and "600112233"). */
fun sameAddress(a: String, b: String): Boolean =
    if ('@' in a || '@' in b) a.equals(b, ignoreCase = true)
    else @Suppress("DEPRECATION") PhoneNumberUtils.compare(a, b)
