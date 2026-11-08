package dev.hostyn.messages.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { System, Light, Dark }

/**
 * App preferences. Each value is Compose state (screens update when it changes) backed by
 * SharedPreferences, so receivers can read it too.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var theme by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.System)
        private set
    fun updateTheme(value: ThemeMode) { theme = value; prefs.edit().putString("theme", value.name).apply() }

    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamic_color", true))
        private set
    fun updateDynamicColor(value: Boolean) { dynamicColor = value; prefs.edit().putBoolean("dynamic_color", value).apply() }

    /** Ask the network for a delivery report for each message sent. */
    var deliveryReports by mutableStateOf(prefs.getBoolean("delivery_reports", false))
        private set
    fun updateDeliveryReports(value: Boolean) { deliveryReports = value; prefs.edit().putBoolean("delivery_reports", value).apply() }

    /** Download MMS (pictures, group messages) as soon as they arrive. */
    var autoDownloadMms by mutableStateOf(prefs.getBoolean("auto_download_mms", true))
        private set
    fun updateAutoDownloadMms(value: Boolean) { autoDownloadMms = value; prefs.edit().putBoolean("auto_download_mms", value).apply() }
}
