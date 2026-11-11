@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.messages.ui.conversations

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.MarkChatRead
import androidx.compose.material.icons.rounded.MarkChatUnread
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hostyn.messages.R
import dev.hostyn.messages.data.Conversation
import dev.hostyn.messages.data.MessagesRepository
import dev.hostyn.messages.otp.OtpDetector
import dev.hostyn.messages.ui.components.CopyCodeButton
import dev.hostyn.messages.ui.components.EmptyState
import dev.hostyn.messages.ui.components.RecipientsAvatar
import dev.hostyn.messages.ui.components.SearchPill
import dev.hostyn.messages.ui.components.foldAccents
import dev.hostyn.messages.ui.components.formatShortTime
import dev.hostyn.messages.ui.components.segmentedColors
import dev.hostyn.messages.ui.theme.LocalTransformOrigin
import dev.hostyn.messages.ui.theme.containerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The new conversation screen grows out of the FAB (container transform). */
const val NewChatTransformKey = "new-chat"

/** Key of the container transform between a conversation's row and the conversation. */
fun conversationTransformKey(threadId: Long) = "conversation-$threadId"

@Composable
fun ConversationsScreen(
    repo: MessagesRepository,
    onOpen: (Long) -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val conversations by repo.conversations.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // The FAB shows its label at the top of the list and shrinks to its icon while scrolling.
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

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
                        IconButton(onClick = { selected = emptySet() }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.close))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val ids = selected
                                selected = emptySet()
                                scope.launch(Dispatchers.IO) {
                                    ids.forEach { if (anyUnread) repo.markRead(it) else repo.markUnread(it) }
                                }
                            },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                if (anyUnread) Icons.Rounded.MarkChatRead else Icons.Rounded.MarkChatUnread,
                                stringResource(if (anyUnread) R.string.mark_read else R.string.mark_unread),
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Delete, stringResource(R.string.delete))
                        }
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
                            IconButton(onClick = { menu = true }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options))
                            }
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
            val origin = LocalTransformOrigin.current
            AnimatedVisibility(!selecting, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                MediumExtendedFloatingActionButton(
                    onClick = { origin.key = NewChatTransformKey; onNewChat() },
                    expanded = fabExpanded,
                    icon = { Icon(Icons.AutoMirrored.Rounded.Chat, null, Modifier.size(FloatingActionButtonDefaults.MediumIconSize)) },
                    text = { Text(stringResource(R.string.start_chat)) },
                    modifier = Modifier.containerTransform(NewChatTransformKey, MaterialTheme.shapes.largeIncreased),
                )
            }
        },
    ) { padding ->
        when {
            conversations == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { LoadingIndicator() }
            shown.isEmpty() -> EmptyState(
                icon = Icons.AutoMirrored.Rounded.Chat,
                title = stringResource(if (query.isBlank()) R.string.no_conversations else R.string.no_results),
                body = stringResource(if (query.isBlank()) R.string.no_conversations_body else R.string.no_results_body),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = 120.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(shown, key = { _, c -> c.threadId }) { i, c ->
                    ConversationRow(
                        c,
                        index = i,
                        count = shown.size,
                        selected = c.threadId in selected,
                        selecting = selecting,
                        onOpen = { onOpen(c.threadId) },
                        onToggle = { toggle(c.threadId) },
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
                TextButton(
                    onClick = {
                        val ids = selected
                        confirmDelete = false
                        selected = emptySet()
                        scope.launch(Dispatchers.IO) { repo.deleteThreads(ids) }
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

/** Codes are usually valid for a few minutes; older ones aren't worth a button in the list. */
private const val CODE_FRESH_MS = 15 * 60 * 1000L

@Composable
private fun ConversationRow(
    c: Conversation,
    index: Int,
    count: Int,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val origin = LocalTransformOrigin.current
    // The conversation grows out of this row (container transform).
    val key = conversationTransformKey(c.threadId)

    Box(
        modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = if (index == count - 1) 0.dp else ListItemDefaults.SegmentedGap),
    ) {
        SegmentedListItem(
            selected = selected,
            onClick = {
                if (selecting) onToggle()
                else {
                    origin.key = key
                    onOpen()
                }
            },
            onLongClick = {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onToggle()
            },
            onLongClickLabel = stringResource(R.string.select),
            shapes = shapes,
            colors = segmentedColors(),
            leadingContent = {
                Box {
                    RecipientsAvatar(c.recipients, size = 48.dp)
                    AnimatedVisibility(selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = colors.onPrimary)
                            }
                        }
                    }
                }
            },
            supportingContent = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.hasError) {
                            Icon(Icons.Rounded.Error, null, tint = colors.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            when {
                                c.hasError -> stringResource(R.string.not_sent)
                                c.snippet.isNotBlank() -> c.snippet.replace('\n', ' ')
                                c.hasAttachment -> stringResource(R.string.attachment_file)
                                else -> stringResource(R.string.mms)
                            },
                            color = when {
                                c.hasError -> colors.error
                                c.unread -> colors.onSurface
                                else -> colors.onSurfaceVariant
                            },
                            fontWeight = if (c.unread) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Fresh verification code: copy it without opening the conversation.
                    val code = remember(c.snippet, c.date) {
                        if (c.hasError || System.currentTimeMillis() - c.date > CODE_FRESH_MS) null else OtpDetector.find(c.snippet)
                    }
                    if (code != null && !selecting) {
                        CopyCodeButton(code, Modifier.padding(top = 8.dp))
                    }
                }
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        formatShortTime(context, c.date),
                        style = if (c.unread) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
                        color = if (c.unread) colors.primary else colors.onSurfaceVariant,
                    )
                    if (c.unread) Badge(containerColor = colors.primary)
                }
            },
            modifier = Modifier.fillMaxWidth().containerTransform(key, shapes.shape),
        ) {
            Text(
                c.title,
                style = if (c.unread) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
