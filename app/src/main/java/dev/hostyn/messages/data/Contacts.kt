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
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves addresses to contacts (cached) and lists contacts' phone numbers.
 *
 * Contacts are named by their own visible name when their account gave one ([customName]), else by
 * their nickname when [AppSettings.preferNicknames], else by the name Android builds.
 */
class Contacts(private val context: Context, private val settings: AppSettings) {
    private val cache = ConcurrentHashMap<String, Recipient>()

    init {
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = cache.clear()
            },
        )
    }

    /** See [AppSettings.preferNicknames]; names change when it does. */
    val preferNicknames get() = settings.preferNicknames

    private val canRead get() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun recipient(address: String): Recipient {
        val preferNicknames = settings.preferNicknames
        // Keyed by the setting too, so turning it on or off renames everyone at once.
        return cache.getOrPut("$preferNicknames:$address") { lookup(address, preferNicknames) }
    }

    private fun lookup(address: String, preferNicknames: Boolean): Recipient {
        // Short codes and alphanumeric senders ("BANK") aren't in the contacts.
        if (!canRead || address.isBlank() || address.none { it.isDigit() } && '@' !in address) return Recipient(address)
        val email = '@' in address
        val uri = if (email) {
            Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(address))
        } else {
            Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        }
        // An email lookup returns data rows, whose _ID is not the contact's.
        val idColumn = if (email) Email.CONTACT_ID else PhoneLookup._ID
        val resolver = context.contentResolver
        return try {
            resolver.query(
                uri,
                arrayOf(idColumn, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(0)
                val fullName = c.getString(1)
                val nickname = resolver.nicknameOf(id)
                val shown = resolver.customNameOf(id) ?: nickname?.takeIf { preferNicknames } ?: fullName
                Recipient(address, shown, c.getString(2), id, otherNames(shown, fullName, nickname))
            } ?: Recipient(address)
        } catch (_: Exception) {
            Recipient(address)
        }
    }

    /** Every phone number in the contacts, sorted by the name shown. */
    fun phones(): List<ContactPhone> {
        if (!canRead) return emptyList()
        val resolver = context.contentResolver
        val names = shownNames(resolver, settings.preferNicknames)
        val out = mutableListOf<ContactPhone>()
        resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI),
            null, null, null,
        )?.use { c ->
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                val number = c.getString(2) ?: continue
                val contactId = c.getLong(0)
                // The same number is often stored twice (e.g. "+34 600…" and "600…") in linked contacts.
                if (!seen.add("$contactId:${PhoneNumberUtils.normalizeNumber(number).takeLast(9)}")) continue
                val label = Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                val name = names[contactId]
                out += ContactPhone(
                    contactId, name?.first ?: c.getString(1) ?: number, number, label, c.getString(5),
                    otherNames = name?.second.orEmpty(),
                )
            }
        }
        // Sorted here rather than by the provider, so a visible name or nickname goes where its own
        // first letter puts it ("Grandma Tere" under G).
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return out.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    /** Shown name and other searchable names of every contact, by contact id. */
    private fun shownNames(resolver: ContentResolver, preferNicknames: Boolean): Map<Long, Pair<String, List<String>>> {
        val visible = HashMap<Long, String>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID, StructuredName.DISPLAY_NAME),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${StructuredName.DISPLAY_NAME} != ''",
            arrayOf(StructuredName.CONTENT_ITEM_TYPE),
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { visible[c.getLong(0)] = it } }
        val nicknames = HashMap<Long, String>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, Nickname.NAME),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${Nickname.NAME} != ''",
            arrayOf(Nickname.CONTENT_ITEM_TYPE),
            "${ContactsContract.Data.IS_SUPER_PRIMARY} DESC, ${ContactsContract.Data.IS_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) c.getString(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { nicknames.putIfAbsent(c.getLong(0), it) }
        }
        val out = HashMap<Long, Pair<String, List<String>>>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID, ContactsContract.Contacts.NAME_RAW_CONTACT_ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val fullName = c.getString(2) ?: continue
                val nickname = nicknames[id]
                val shown = customName(visible[c.getLong(1)], fullName, c.getString(3)) ?: nickname?.takeIf { preferNicknames } ?: fullName
                out[id] = shown to otherNames(shown, fullName, nickname)
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

/** The full name and nickname, when they are not the name shown: search finds them too. */
private fun otherNames(shown: String?, fullName: String?, nickname: String?): List<String> =
    listOfNotNull(fullName, nickname).filter { !it.equals(shown, ignoreCase = true) }.distinct()

/** The contact's nickname ("Grandma"), if they have one. */
fun ContentResolver.nicknameOf(contactId: Long): String? = try {
    query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(Nickname.NAME),
        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${Nickname.NAME} != ''",
        arrayOf(contactId.toString(), Nickname.CONTENT_ITEM_TYPE),
        "${ContactsContract.Data.IS_SUPER_PRIMARY} DESC, ${ContactsContract.Data.IS_PRIMARY} DESC",
    )?.use { c -> if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null }
} catch (_: Exception) {
    null
}

/**
 * The contact's own visible name ("Grandma Tere"), when its source gave one that is not just the
 * first name and surnames. Android shows the name it builds from those whenever a contact has them,
 * and keeps the source's display name (vCard FN) unused in the name row.
 */
fun customName(visible: String?, fullName: String?, fullNameAlt: String?): String? =
    visible?.trim()?.takeIf { v -> v.isNotEmpty() && listOf(fullName, fullNameAlt).none { it?.trim().equals(v, ignoreCase = true) } }

/** [customName] of a contact, read from the raw contact that names it. */
fun ContentResolver.customNameOf(contactId: Long): String? = try {
    query(
        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
        arrayOf(ContactsContract.Contacts.NAME_RAW_CONTACT_ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE),
        null, null, null,
    )?.use { c ->
        if (!c.moveToFirst()) return null
        val visible = query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(StructuredName.DISPLAY_NAME),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(c.getLong(0).toString(), StructuredName.CONTENT_ITEM_TYPE),
            null,
        )?.use { n -> if (n.moveToFirst()) n.getString(0) else null }
        customName(visible, c.getString(1), c.getString(2))
    }
} catch (_: Exception) {
    null
}

/** Same phone number in different formats ("+34 600 11 22 33" and "600112233"). */
fun sameAddress(a: String, b: String): Boolean =
    if ('@' in a || '@' in b) a.equals(b, ignoreCase = true)
    else @Suppress("DEPRECATION") PhoneNumberUtils.compare(a, b)
