@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.messages.ui.components

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.foundation.layout.heightIn
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Patterns
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hostyn.messages.R
import dev.hostyn.messages.data.Attachment
import dev.hostyn.messages.data.Recipient
import java.text.Normalizer
import java.util.Calendar

fun String.foldAccents(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Value recomputed every time the screen comes back to the foreground. */
@Composable
fun <T> rememberOnResume(compute: () -> T): T {
    var value by remember { mutableStateOf(compute()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) value = compute() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return value
}

/** One avatar for a person, two overlapping ones for a group. */
@Composable
fun RecipientsAvatar(recipients: List<Recipient>, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    if (recipients.size <= 1) {
        val r = recipients.firstOrNull() ?: Recipient("")
        ContactAvatar(r.name ?: "", r.photoUri, r.seed, modifier, size)
        return
    }
    val small = size * 0.68f
    Box(modifier.size(size)) {
        val (a, b) = recipients
        ContactAvatar(a.name ?: "", a.photoUri, a.seed, Modifier.align(Alignment.TopStart), small)
        ContactAvatar(
            b.name ?: "", b.photoUri, b.seed,
            Modifier
                .align(Alignment.BottomEnd)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            small,
        )
    }
}

@Composable
fun SearchPill(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp)) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onQuery(""); focus.clearFocus() }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Close, null)
                }
            }
            if (query.isEmpty() && trailing != null) trailing()
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // An expressive shape draws the eye to the empty state's icon.
        Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(104.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Small badge on top of something (unread dot). */
@Composable
fun Dot(modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape))
}

// --- Text ---

fun attachmentLabel(context: Context, a: Attachment): String = context.getString(
    when {
        a.isImage -> R.string.attachment_photo
        a.isVideo -> R.string.attachment_video
        a.isAudio -> R.string.attachment_audio
        a.isVCard -> R.string.attachment_contact
        else -> R.string.attachment_file
    },
)

private fun Calendar.dayKey() = get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)

/** "12:30", "Tue", "8 Nov" or "8/11/24", for the conversation list. */
fun formatShortTime(context: Context, millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    return when {
        now.dayKey() == then.dayKey() -> DateFormat.getTimeFormat(context).format(then.time)
        now.timeInMillis - millis < 6 * DateUtils.DAY_IN_MILLIS ->
            DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
        else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
    }
}

fun formatTime(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(millis)

/** "Today", "Yesterday", "Saturday, 8 November" or "8 November 2024", between messages. */
fun formatDay(context: Context, millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        now.dayKey() == then.dayKey() -> context.getString(R.string.today)
        yesterday.dayKey() == then.dayKey() -> context.getString(R.string.yesterday)
        now.timeInMillis - millis < 6 * DateUtils.DAY_IN_MILLIS ->
            DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
        else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
    }
}

fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.dayKey() == cb.dayKey()
}

/** Text with tappable web links, emails and phone numbers ([code], a verification code, isn't a phone). */
fun linkify(text: String, styles: TextLinkStyles, code: String? = null): AnnotatedString {
    data class Link(val start: Int, val end: Int, val url: String)
    val links = mutableListOf<Link>()
    fun scan(pattern: java.util.regex.Pattern, toUrl: (String) -> String?) {
        val m = pattern.matcher(text)
        while (m.find()) {
            if (links.any { m.start() < it.end && m.end() > it.start }) continue
            val url = toUrl(m.group()) ?: continue
            links += Link(m.start(), m.end(), url)
        }
    }
    scan(Patterns.EMAIL_ADDRESS) { "mailto:$it" }
    scan(Patterns.WEB_URL) { if (it.startsWith("http", true)) it else if ('.' in it && it.length > 4) "https://$it" else null }
    scan(Patterns.PHONE) { phone ->
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 6 || digits == code) null else "tel:${phone.filter { c -> c.isDigit() || c == '+' }}"
    }
    if (links.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        links.forEach { addLink(LinkAnnotation.Url(it.url, styles), it.start, it.end) }
    }
}

fun linkStyles(color: androidx.compose.ui.graphics.Color) =
    TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline))

/** Compact "Copy 123456" button for verification codes. */
@Composable
fun CopyCodeButton(code: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.FilledTonalButton(
        onClick = { dev.hostyn.messages.otp.copyCode(context, code) },
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight),
        modifier = modifier.heightIn(ButtonDefaults.ExtraSmallContainerHeight),
    ) {
        Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.ExtraSmallContainerHeight)))
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.ExtraSmallContainerHeight)))
        Text(
            androidx.compose.ui.res.stringResource(R.string.copy_code_value, code),
            style = ButtonDefaults.textStyleFor(ButtonDefaults.ExtraSmallContainerHeight),
        )
    }
}
