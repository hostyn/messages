package dev.hostyn.messages.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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

@OptIn(ExperimentalMaterial3Api::class)
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
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            item {
                Section(stringResource(R.string.messages_settings)) {
                    SettingRow(
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
                    SwitchRow(
                        icon = Icons.Rounded.DoneAll,
                        title = stringResource(R.string.delivery_reports),
                        summary = stringResource(R.string.delivery_reports_summary),
                        checked = settings.deliveryReports,
                        onChange = settings::updateDeliveryReports,
                    )
                    SwitchRow(
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
                    SettingRow(
                        icon = Icons.Rounded.DarkMode,
                        title = stringResource(R.string.theme),
                        summary = stringResource(themeLabel(settings.theme)),
                        onClick = { choosingTheme = true },
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
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
                    SettingRow(icon = Icons.Rounded.Info, title = stringResource(R.string.version), summary = version.orEmpty())
                    SettingRow(
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
                            .clip(RoundedCornerShape(12.dp))
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 76.dp, top = 24.dp, bottom = 4.dp),
    )
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) { content() }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, summary: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(icon = icon, title = title, summary = summary, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
