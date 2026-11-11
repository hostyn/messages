@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.messages.ui.settings

import dev.hostyn.messages.ui.components.segmentedColors
import dev.hostyn.messages.ui.components.SegmentedGroupScope
import dev.hostyn.messages.ui.components.SegmentedGroup
import dev.hostyn.messages.ui.components.ListHeader
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonDefaults
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.hostyn.messages.R
import dev.hostyn.messages.data.AppSettings
import dev.hostyn.messages.data.ThemeMode

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var choosingTheme by remember { mutableStateOf(false) }

    fun open(intent: Intent) = try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.error_no_app), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            item {
                Section(stringResource(R.string.messages_settings)) {
                    setting(
                        icon = Icons.Rounded.Notifications,
                        title = stringResource(R.string.notifications),
                        summary = stringResource(R.string.notifications_summary),
                        onClick = {
                            open(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        },
                    )
                    toggle(
                        icon = Icons.Rounded.DoneAll,
                        title = stringResource(R.string.delivery_reports),
                        summary = stringResource(R.string.delivery_reports_summary),
                        checked = settings.deliveryReports,
                        onChange = settings::updateDeliveryReports,
                    )
                    toggle(
                        icon = Icons.Rounded.Download,
                        title = stringResource(R.string.auto_download_mms),
                        summary = stringResource(R.string.auto_download_mms_summary),
                        checked = settings.autoDownloadMms,
                        onChange = settings::updateAutoDownloadMms,
                    )
                }
            }
            item {
                Section(stringResource(R.string.appearance)) {
                    setting(
                        icon = Icons.Rounded.DarkMode,
                        title = stringResource(R.string.theme),
                        summary = stringResource(themeLabel(settings.theme)),
                        onClick = { choosingTheme = true },
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        toggle(
                            icon = Icons.Rounded.Palette,
                            title = stringResource(R.string.dynamic_color),
                            summary = stringResource(R.string.dynamic_color_summary),
                            checked = settings.dynamicColor,
                            onChange = settings::updateDynamicColor,
                        )
                    }
                }
            }
            item {
                Section(stringResource(R.string.about)) {
                    val version = remember {
                        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { null }
                    }
                    setting(icon = Icons.Rounded.Info, title = stringResource(R.string.version), summary = version.orEmpty())
                    setting(
                        icon = Icons.Rounded.Code,
                        title = stringResource(R.string.source_code),
                        summary = "github.com/hostyn/messages",
                        onClick = { open(Intent(Intent.ACTION_VIEW, "https://github.com/hostyn/messages".toUri())) },
                    )
                }
            }
        }
    }

    if (choosingTheme) {
        ChoiceDialog(
            title = stringResource(R.string.theme),
            labels = ThemeMode.entries.map { stringResource(themeLabel(it)) },
            selected = ThemeMode.entries.indexOf(settings.theme),
            onSelect = { settings.updateTheme(ThemeMode.entries[it]) },
            onDismiss = { choosingTheme = false },
        )
    }
}

private fun themeLabel(t: ThemeMode) = when (t) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.Dark -> R.string.theme_dark
}

@Composable
private fun ChoiceDialog(title: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                labels.forEachIndexed { i, label ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(selected = i == selected, role = Role.RadioButton) {
                                onSelect(i)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        RadioButton(selected = i == selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A titled M3 segmented list of settings. */
// Inline, like SegmentedGroup, so a switch's new value redraws its item (see SegmentedGroup).
@Composable
private inline fun Section(title: String, content: @Composable SegmentedGroupScope.() -> Unit) {
    ListHeader(title, Modifier.padding(top = 8.dp))
    SegmentedGroup(content = content)
}

/** A setting: tap to change it, or plain information without [onClick]. */
private fun SegmentedGroupScope.setting(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
) = item { shapes ->
    val leading: @Composable () -> Unit = { Icon(icon, null) }
    val supporting: (@Composable () -> Unit)? = summary?.takeIf { it.isNotEmpty() }?.let { { Text(it) } }
    if (onClick != null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            colors = segmentedColors(),
            leadingContent = leading,
            supportingContent = supporting,
        ) { Text(title) }
    } else {
        SegmentedListItem(
            shapes = shapes,
            colors = segmentedColors(),
            leadingContent = leading,
            supportingContent = supporting,
        ) { Text(title) }
    }
}

/** An on/off setting: the whole item toggles the switch. */
private fun SegmentedGroupScope.toggle(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) = item { shapes ->
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = shapes,
        colors = segmentedColors(),
        leadingContent = { Icon(icon, null) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(title) }
}
