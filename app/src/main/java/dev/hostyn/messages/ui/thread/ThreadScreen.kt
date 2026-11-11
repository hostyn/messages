@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.messages.ui.thread

import dev.hostyn.messages.ui.theme.containerTransform
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonDefaults
import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import dev.hostyn.messages.MessagesApp
import dev.hostyn.messages.R
import dev.hostyn.messages.data.Attachment
import dev.hostyn.messages.data.Message
import dev.hostyn.messages.data.MessageStatus
import dev.hostyn.messages.data.Recipient
import dev.hostyn.messages.mms.MmsDownloader
import dev.hostyn.messages.mms.MmsSender
import dev.hostyn.messages.notify.Notifications
import dev.hostyn.messages.otp.OtpDetector
import dev.hostyn.messages.otp.copyCode
import dev.hostyn.messages.sms.SmsSender
import dev.hostyn.messages.sms.defaultSubId
import dev.hostyn.messages.ui.components.ContactAvatar
import dev.hostyn.messages.ui.components.CopyCodeButton
import dev.hostyn.messages.ui.components.RecipientsAvatar
import dev.hostyn.messages.ui.components.attachmentLabel
import dev.hostyn.messages.ui.components.formatDay
import dev.hostyn.messages.ui.components.formatTime
import dev.hostyn.messages.ui.components.isSameDay
import dev.hostyn.messages.ui.components.linkStyles
import dev.hostyn.messages.ui.components.linkify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Messages closer than this (from the same person) are drawn as one group. */
private const val GROUP_GAP = 3 * 60 * 1000L

@Composable
fun ThreadScreen(
    threadId: Long,
    initialText: String = "",
    initialAttachments: List<Uri> = emptyList(),
    transformKey: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = MessagesApp.of(context)
    val repo = app.repository
    val scope = rememberCoroutineScope()
    // Start with the recipients the conversation list already knows, so the header is there while
    // the conversation grows out of its row.
    val known = remember(threadId) { repo.conversations.value?.firstOrNull { it.threadId == threadId }?.recipients }
    val recipients by remember(threadId) { repo.recipientsFlow(threadId) }.collectAsState(initial = known.orEmpty())
    val messages by remember(threadId) { repo.messages(threadId) }.collectAsState(initial = null)
    val listState = rememberLazyListState()

    var text by rememberSaveable(threadId) { mutableStateOf(initialText) }
    val attachments = remember(threadId) { mutableStateListOf<Uri>().apply { addAll(initialAttachments) } }
    val sims = remember { activeSims(context) }
    var subId by rememberSaveable(threadId) { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Attachment?>(null) }

    val isGroup = recipients.size > 1
    val single = recipients.singleOrNull()

    // While this conversation is on screen its messages are read and don't notify.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, threadId) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> app.openThreadId = threadId
                Lifecycle.Event.ON_PAUSE -> if (app.openThreadId == threadId) app.openThreadId = null
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (app.openThreadId == threadId) app.openThreadId = null
        }
    }
    LaunchedEffect(messages) {
        val list = messages ?: return@LaunchedEffect
        if (list.any { it.incoming && !it.read }) withContext(Dispatchers.IO) { repo.markRead(threadId) }
        Notifications.cancel(context, threadId)
        // Use the SIM of the conversation, unless one was chosen.
        if (subId == null) subId = list.lastOrNull { it.subId >= 0 }?.subId?.takeIf { id -> sims.any { it.subscriptionId == id } } ?: defaultSubId()
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        attachments.addAll(uris.filterNot { it in attachments })
    }

    fun send() {
        val body = text.trim()
        val files = attachments.toList()
        val to = recipients.map { it.address }
        if (to.isEmpty() || (body.isEmpty() && files.isEmpty())) return
        text = ""
        attachments.clear()
        val sim = subId ?: defaultSubId()
        app.scope.launch {
            val uri = if (to.size > 1 || files.isNotEmpty()) MmsSender.send(context, to, body, files, sim)
            else SmsSender.send(context, to.first(), body, sim)
            if (uri == null) withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.error_send, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        // Grows out of the conversation's row in the list (container transform).
        modifier = Modifier.containerTransform(transformKey),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RecipientsAvatar(recipients, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                recipients.joinToString(", ") { it.displayName },
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = when {
                                isGroup -> stringResource(R.string.participants, recipients.size)
                                single?.name != null -> single.address
                                else -> null
                            }
                            if (subtitle != null) {
                                Text(
                                    subtitle, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (single != null && single.address.any(Char::isDigit) && single.address.length > 5) {
                        IconButton(onClick = { context.dial(single.address) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Call, stringResource(R.string.call)) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (single != null && single.contactId == null && single.address.any(Char::isDigit)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.add_contact)) },
                                    leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) },
                                    onClick = { menu = false; context.addContact(single.address) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_conversation)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Composer(
                text = text,
                onText = { text = it },
                attachments = attachments,
                onRemove = { attachments.remove(it) },
                onAttach = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                sims = sims,
                subId = subId,
                onSim = { subId = it },
                isMms = isGroup || attachments.isNotEmpty(),
                onSend = ::send,
            )
        },
    ) { padding ->
        val list = messages.orEmpty().asReversed()
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list.size, key = { list[it].key }) { i ->
                val m = list[i]
                val older = list.getOrNull(i + 1)
                val newer = list.getOrNull(i - 1)
                val firstOfGroup = older == null || !sameGroup(older, m)
                val lastOfGroup = newer == null || !sameGroup(m, newer)
                Column {
                    if (older == null || !isSameDay(older.date, m.date)) {
                        Text(
                            formatDay(context, m.date),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                    MessageBubble(
                        m,
                        sender = if (isGroup && m.incoming) recipients.firstOrNull { it.address == m.sender } ?: m.sender?.let(app.contacts::recipient) else null,
                        firstOfGroup = firstOfGroup,
                        lastOfGroup = lastOfGroup,
                        onOpenAttachment = { a -> if (a.isImage) viewing = a else context.openAttachment(a) },
                        onRetry = {
                            app.scope.launch {
                                val uri = repo.messageUri(m)
                                if (m.isMms) MmsSender.retry(context, uri) else SmsSender.retry(context, uri)
                            }
                        },
                        onDownload = { app.scope.launch { MmsDownloader.download(context, repo.messageUri(m)) } },
                        onDelete = { app.scope.launch { repo.deleteMessage(m) } },
                    )
                }
            }
        }
    }

    viewing?.let { a -> ImageViewer(a) { viewing = null } }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(stringResource(R.string.delete_conversation)) },
            text = { Text(stringResource(R.string.delete_conversations_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch(Dispatchers.IO) { repo.deleteThreads(listOf(threadId)) }
                        onBack()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun sameGroup(a: Message, b: Message) =
    a.incoming == b.incoming && a.sender == b.sender && b.date - a.date < GROUP_GAP && isSameDay(a.date, b.date)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: Message,
    sender: Recipient?,
    firstOfGroup: Boolean,
    lastOfGroup: Boolean,
    onOpenAttachment: (Attachment) -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val outgoing = !m.incoming
    var menu by remember { mutableStateOf(false) }
    // Expressive bubbles: rounded corners from the shape scale, tighter ones where messages of
    // the same group meet.
    val big = MaterialTheme.shapes.largeIncreased.topStart
    val small = MaterialTheme.shapes.extraSmall.topStart
    val shape = if (outgoing) {
        RoundedCornerShape(big, if (firstOfGroup) big else small, if (lastOfGroup) big else small, big)
    } else {
        RoundedCornerShape(if (firstOfGroup) big else small, big, big, if (lastOfGroup) big else small)
    }
    val container = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val failed = m.status == MessageStatus.Failed
    val code = remember(m.body) { if (m.incoming) OtpDetector.find(m.body) else null }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = if (firstOfGroup) 8.dp else 2.dp),
    ) {
        if (sender != null) {
            if (lastOfGroup) ContactAvatar(sender.name ?: "", sender.photoUri, sender.seed, size = 32.dp)
            else Spacer(Modifier.width(32.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 300.dp)) {
            if (sender != null && firstOfGroup) {
                Text(
                    sender.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
            Box {
                Column(
                    horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .clip(shape)
                        .combinedClickable(onClick = { if (failed) onRetry() }, onLongClick = { menu = true }),
                ) {
                    m.attachments.filter { it.isImage }.forEach { a ->
                        AsyncImage(
                            model = a.uri,
                            contentDescription = stringResource(R.string.attachment_photo),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .heightIn(min = 80.dp, max = 320.dp)
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { onOpenAttachment(a) },
                        )
                    }
                    m.attachments.filterNot { it.isImage }.forEach { a ->
                        Surface(shape = MaterialTheme.shapes.large, color = container, modifier = Modifier.clickable { onOpenAttachment(a) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Icon(
                                    when {
                                        a.isVideo -> Icons.Rounded.Videocam
                                        a.isAudio -> Icons.Rounded.Audiotrack
                                        a.isVCard -> Icons.Rounded.ContactPage
                                        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                                    },
                                    null, tint = content,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(a.name ?: attachmentLabel(context, a), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (m.pendingDownload) {
                        PendingMms(m, container, content, onDownload)
                    }
                    val bodyText = listOfNotNull(m.subject?.takeIf { m.isMms }, m.body.takeIf { it.isNotBlank() })
                    if (bodyText.isNotEmpty()) {
                        Surface(shape = shape, color = container) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                if (m.subject != null && m.isMms) {
                                    Text(m.subject, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = content)
                                }
                                if (m.body.isNotBlank()) {
                                    val linkColor = if (outgoing) content else MaterialTheme.colorScheme.primary
                                    Text(
                                        remember(m.body, linkColor) { linkify(m.body, linkStyles(linkColor), code) },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = content,
                                    )
                                }
                            }
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (code != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_code)) },
                            leadingIcon = { Icon(Icons.Rounded.Password, null) },
                            onClick = { menu = false; copyCode(context, code) },
                        )
                    }
                    if (m.body.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_text)) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            onClick = {
                                menu = false
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText(null, m.body))
                            },
                        )
                    }
                    if (failed) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.retry)) },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                            onClick = { menu = false; onRetry() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
            if (code != null) {
                CopyCodeButton(code, Modifier.padding(top = 6.dp))
            }
            if (lastOfGroup || failed || m.status == MessageStatus.Sending) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (failed) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        statusLine(context, m),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun statusLine(context: Context, m: Message): String {
    val time = formatTime(context, m.date)
    val status = when (m.status) {
        MessageStatus.Sending -> context.getString(R.string.status_sending)
        MessageStatus.Sent -> null
        MessageStatus.Delivered -> context.getString(R.string.status_delivered)
        MessageStatus.Failed -> context.getString(R.string.status_failed)
        MessageStatus.Received -> null
    }
    return if (m.status == MessageStatus.Sending || m.status == MessageStatus.Failed) status!!
    else listOfNotNull(time, status).joinToString(" · ")
}

@Composable
private fun PendingMms(m: Message, container: Color, content: Color, onDownload: () -> Unit) {
    val context = LocalContext.current
    Surface(shape = MaterialTheme.shapes.largeIncreased, color = container) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.mms_pending), style = MaterialTheme.typography.titleSmallEmphasized, color = content)
            if (m.sizeBytes > 0) {
                Text(
                    Formatter.formatShortFileSize(context, m.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (m.downloading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.downloading), color = content)
                }
            } else {
                FilledTonalButton(onClick = onDownload, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.download))
                }
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    attachments: List<Uri>,
    onRemove: (Uri) -> Unit,
    onAttach: () -> Unit,
    sims: List<SubscriptionInfo>,
    subId: Int?,
    onSim: (Int) -> Unit,
    isMms: Boolean,
    onSend: () -> Unit,
) {
    var simMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            if (attachments.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    items(attachments, key = { it.toString() }) { uri ->
                        Box {
                            AsyncImage(
                                model = uri, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(88.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                            FilledIconButton(
                                onClick = { onRemove(uri) },
                                shapes = IconButtonDefaults.shapes(),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                                ),
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp),
                            ) { Icon(Icons.Rounded.Close, stringResource(R.string.remove), Modifier.size(16.dp)) }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, shapes = IconButtonDefaults.shapes(), modifier = Modifier.padding(bottom = 6.dp)) {
                    Icon(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.attach_photo), tint = MaterialTheme.colorScheme.primary)
                }
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 4.dp)) {
                        Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                            if (text.isEmpty()) {
                                Text(
                                    stringResource(if (isMms) R.string.compose_mms else R.string.compose_sms),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = onText,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (sims.size > 1) {
                            Box {
                                val index = sims.indexOfFirst { it.subscriptionId == subId }.coerceAtLeast(0)
                                TextButton(onClick = { simMenu = true }, shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Rounded.SimCard, null, modifier = Modifier.size(18.dp))
                                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                                }
                                DropdownMenu(expanded = simMenu, onDismissRequest = { simMenu = false }) {
                                    sims.forEachIndexed { i, sim ->
                                        DropdownMenuItem(
                                            text = { Text("SIM ${i + 1} · ${sim.displayName}") },
                                            leadingIcon = { Icon(Icons.Rounded.SimCard, null) },
                                            onClick = { simMenu = false; onSim(sim.subscriptionId) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!isMms && text.length > 120) {
                        val info = remember(text) { SmsMessage.calculateLength(text, false) }
                        Text(
                            if (info[0] > 1) "${info[2]} / ${info[0]}" else "${info[2]}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Medium icon button: round at rest, it squares off while pressed.
                    FilledIconButton(
                        onClick = onSend,
                        enabled = text.isNotBlank() || attachments.isNotEmpty(),
                        shapes = IconButtonDefaults.shapes(IconButtonDefaults.mediumRoundShape, IconButtonDefaults.mediumPressedShape),
                        modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.send), Modifier.size(IconButtonDefaults.mediumIconSize))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageViewer(attachment: Attachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = attachment.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
            )
            Row(Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 8.dp)) {
                IconButton(onClick = { context.shareAttachment(attachment) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Share, stringResource(R.string.share), tint = Color.White)
                }
                IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = Color.White)
                }
            }
        }
    }
}

// --- Helpers ---

@SuppressLint("MissingPermission")
private fun activeSims(context: Context): List<SubscriptionInfo> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return try {
        context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun Context.dial(number: String) {
    try {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    } catch (_: Exception) {}
}

private fun Context.addContact(number: String) {
    try {
        startActivity(
            Intent(ContactsContract.Intents.Insert.ACTION)
                .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, number),
        )
    } catch (_: Exception) {}
}

/** Copies an MMS part to our cache so other apps can open it (they can't read the MMS provider). */
private fun Context.exportPart(a: Attachment): Uri? = try {
    val dir = File(cacheDir, "shared").apply { mkdirs() }
    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(a.contentType) ?: "bin"
    val name = a.name?.substringAfterLast('/')?.takeIf { '.' in it } ?: "attachment-${a.partId}.$ext"
    val file = File(dir, name)
    contentResolver.openInputStream(a.uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
    FileProvider.getUriForFile(this, "$packageName.files", file)
} catch (_: Exception) {
    null
}

private fun Context.openAttachment(a: Attachment) {
    val uri = exportPart(a) ?: return
    try {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.contentType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) {
        Toast.makeText(this, R.string.error_no_app, Toast.LENGTH_SHORT).show()
    }
}

private fun Context.shareAttachment(a: Attachment) {
    val uri = exportPart(a) ?: return
    val send = Intent(Intent.ACTION_SEND).setType(a.contentType).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}

