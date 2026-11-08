package dev.hostyn.messages.ui.newchat

import android.telephony.PhoneNumberUtils
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hostyn.messages.MessagesApp
import dev.hostyn.messages.R
import dev.hostyn.messages.data.ContactPhone
import dev.hostyn.messages.data.sameAddress
import dev.hostyn.messages.ui.components.ContactAvatar
import dev.hostyn.messages.ui.components.foldAccents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Picked(val address: String, val name: String?, val photoUri: String?, val contactId: Long?)

/** Pick one or more recipients, then open (or create) their conversation. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NewChatScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val app = MessagesApp.of(context)
    val scope = rememberCoroutineScope()
    val phones by produceState(initialValue = emptyList<ContactPhone>()) {
        value = withContext(Dispatchers.IO) { app.contacts.phones() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var dialpad by rememberSaveable { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Picked>() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val folded = query.trim().foldAccents()
    val digits = query.filter { it.isDigit() }
    val results = remember(phones, folded, digits) {
        if (folded.isEmpty()) phones
        else phones.filter { p ->
            p.name.foldAccents().split(' ').any { it.startsWith(folded) } ||
                p.name.foldAccents().contains(folded) ||
                (digits.length >= 3 && p.number.filter(Char::isDigit).contains(digits))
        }
    }
    val typedNumber = query.trim().takeIf { q ->
        q.count(Char::isDigit) >= 3 && q.all { it.isDigit() || it in "+-() ." }
    }

    fun toggle(p: Picked) {
        val existing = picked.firstOrNull { sameAddress(it.address, p.address) }
        if (existing != null) picked.remove(existing) else picked.add(p)
        query = ""
    }

    fun open(list: List<Picked>) {
        if (list.isEmpty()) return
        scope.launch {
            val id = withContext(Dispatchers.IO) { app.repository.threadIdFor(list.map { it.address }) }
            onOpen(id)
        }
    }

    fun tapOrToggle(p: Picked) {
        if (picked.isEmpty()) open(listOf(p)) else toggle(p)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.new_conversation)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            if (picked.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { open(picked.toList()) },
                    icon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) },
                    text = { Text(stringResource(if (picked.size > 1) R.string.start_group else R.string.next)) },
                    modifier = Modifier.imePadding(),
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 4.dp)) {
                    Text(stringResource(R.string.to), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.type_name_or_number),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (dialpad) KeyboardType.Phone else KeyboardType.Text,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                when {
                                    typedNumber != null -> tapOrToggle(Picked(typedNumber, null, null, null))
                                    picked.isNotEmpty() -> open(picked.toList())
                                }
                            }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    IconButton(onClick = { dialpad = !dialpad; focus.requestFocus() }) {
                        Icon(
                            if (dialpad) Icons.Rounded.Keyboard else Icons.Rounded.Dialpad,
                            stringResource(if (dialpad) R.string.keyboard_text else R.string.keyboard_numbers),
                        )
                    }
                }
            }
            if (picked.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(picked, key = { it.address }) { p ->
                        InputChip(
                            selected = true,
                            onClick = { picked.remove(p) },
                            label = { Text(p.name ?: p.address) },
                            avatar = { ContactAvatar(p.name ?: "", p.photoUri, p.contactId ?: p.address.hashCode().toLong(), size = InputChipDefaults.AvatarSize) },
                            trailingIcon = { Icon(Icons.Rounded.Close, stringResource(R.string.remove), Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.weight(1f).imePadding()) {
                if (typedNumber != null) {
                    item(key = "typed") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { tapOrToggle(Picked(typedNumber, null, null, null)) },
                                    onLongClick = { toggle(Picked(typedNumber, null, null, null)) },
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(stringResource(R.string.send_to_number, PhoneNumberUtils.formatNumber(typedNumber, java.util.Locale.getDefault().country) ?: typedNumber))
                            }
                        }
                    }
                }
                items(results, key = { "${it.contactId}:${it.number}" }) { p ->
                    val selected = picked.any { sameAddress(it.address, p.number) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                // A tap opens the chat; a long press starts picking several people for a group.
                                onClick = { tapOrToggle(Picked(p.number, p.name, p.photoUri, p.contactId)) },
                                onLongClick = { toggle(Picked(p.number, p.name, p.photoUri, p.contactId)) },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Box {
                            ContactAvatar(p.name, p.photoUri, p.contactId, size = 44.dp)
                            if (selected) {
                                Icon(
                                    Icons.Rounded.CheckCircle, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.BottomEnd).size(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(p.label, p.number).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
