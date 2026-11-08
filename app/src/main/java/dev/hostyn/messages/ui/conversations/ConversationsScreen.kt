package dev.hostyn.messages.ui.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.MarkChatUnread
import androidx.compose.material.icons.rounded.MarkChatRead
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hostyn.messages.R
import dev.hostyn.messages.otp.OtpDetector
import dev.hostyn.messages.ui.components.CopyCodeButton
import dev.hostyn.messages.data.Conversation
import dev.hostyn.messages.data.MessagesRepository
import dev.hostyn.messages.ui.components.EmptyState
import dev.hostyn.messages.ui.components.RecipientsAvatar
import dev.hostyn.messages.ui.components.SearchPill
import dev.hostyn.messages.ui.components.foldAccents
import dev.hostyn.messages.ui.components.formatShortTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(
    repo: MessagesRepository,
    onOpen: (Long) -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversations by remember { repo.conversations() }.collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val shown = remember(conversations, query) {
        val q = query.trim().foldAccents()
        conversations.orEmpty().filter { c ->
            q.isEmpty() || c.title.foldAccents().contains(q) || c.snippet.foldAccents().contains(q) ||
                c.recipients.any { r -> r.address.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifEmpty { "\u0000" }) }
        }
    }
    val selecting = selected.isNotEmpty()
    BackHandler(selecting) { selected = emptySet() }

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (selecting) {
                val chosen = conversations.orEmpty().filter { it.threadId in selected }
                val anyUnread = chosen.any { it.unread }
                TopAppBar(
                    title = { Text(selected.size.toString()) },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
                    },
                    actions = {
                        IconButton(onClick = {
                            val ids = selected
                            selected = emptySet()
                            scope.launch(Dispatchers.IO) {
                                ids.forEach { if (anyUnread) repo.markRead(it) else repo.markUnread(it) }
                            }
                        }) {
                            Icon(
                                if (anyUnread) Icons.Rounded.MarkChatRead else Icons.Rounded.MarkChatUnread,
                                stringResource(if (anyUnread) R.string.mark_read else R.string.mark_unread),
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            } else {
                SearchPill(
                    query = query,
                    onQuery = { query = it },
                    placeholder = stringResource(R.string.search_conversations),
                    trailing = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.mark_all_read)) },
                                    leadingIcon = { Icon(Icons.Rounded.DoneAll, null) },
                                    onClick = {
                                        menu = false
                                        val unread = conversations.orEmpty().filter { it.unread }.map { it.threadId }
                                        scope.launch(Dispatchers.IO) { unread.forEach(repo::markRead) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings)) },
                                    leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                    onClick = { menu = false; onSettings() },
                                )
                            }
                        }
                    },
                    modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    icon = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                    text = { Text(stringResource(R.string.start_chat)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { padding ->
        when {
            conversations == null -> Unit
            shown.isEmpty() -> EmptyState(
                icon = Icons.AutoMirrored.Rounded.Chat,
                title = stringResource(if (query.isBlank()) R.string.no_conversations else R.string.no_results),
                body = stringResource(if (query.isBlank()) R.string.no_conversations_body else R.string.no_results_body),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(shown, key = { _, c -> c.threadId }) { i, c ->
                    ConversationRow(
                        c,
                        selected = c.threadId in selected,
                        first = i == 0,
                        last = i == shown.lastIndex,
                        onClick = { if (selecting) toggle(c.threadId) else onOpen(c.threadId) },
                        onLongClick = { toggle(c.threadId) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(pluralStringResource(R.plurals.delete_conversations_title, selected.size, selected.size)) },
            text = { Text(stringResource(R.string.delete_conversations_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected
                    confirmDelete = false
                    selected = emptySet()
                    scope.launch(Dispatchers.IO) { repo.deleteThreads(ids) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Codes are usually valid for a few minutes; older ones aren't worth a button in the list. */
private const val CODE_FRESH_MS = 15 * 60 * 1000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: Conversation,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val big = 24.dp
    val small = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (first) big else small, topEnd = if (first) big else small,
        bottomStart = if (last) big else small, bottomEnd = if (last) big else small,
    )
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Box {
                RecipientsAvatar(c.recipients, size = 48.dp)
                if (selected) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatShortTime(context, c.date),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (c.unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.hasError) {
                        Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        when {
                            c.hasError -> stringResource(R.string.not_sent)
                            c.snippet.isNotBlank() -> c.snippet.replace('\n', ' ')
                            c.hasAttachment -> stringResource(R.string.attachment_file)
                            else -> stringResource(R.string.mms)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            c.hasError -> MaterialTheme.colorScheme.error
                            c.unread -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (c.unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (c.unread) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) {}
                        }
                    }
                }
                // Fresh verification code: copy it without opening the conversation.
                val code = remember(c.snippet, c.date) {
                    if (c.hasError || System.currentTimeMillis() - c.date > CODE_FRESH_MS) null else OtpDetector.find(c.snippet)
                }
                if (code != null && !selected) {
                    CopyCodeButton(code, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
