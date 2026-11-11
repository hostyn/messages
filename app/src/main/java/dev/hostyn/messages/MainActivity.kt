package dev.hostyn.messages

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.hostyn.messages.notify.Notifications
import dev.hostyn.messages.sms.recipientsOf
import dev.hostyn.messages.ui.SetupGate
import dev.hostyn.messages.ui.conversations.ConversationsScreen
import dev.hostyn.messages.ui.newchat.NewChatScreen
import dev.hostyn.messages.ui.settings.SettingsScreen
import dev.hostyn.messages.ui.theme.LocalNavAnimatedScope
import dev.hostyn.messages.ui.theme.LocalSharedTransitionScope
import dev.hostyn.messages.ui.theme.LocalTransformOrigin
import dev.hostyn.messages.ui.theme.MessagesTheme
import dev.hostyn.messages.ui.theme.TransformOrigin
import dev.hostyn.messages.ui.theme.backwardEnter
import dev.hostyn.messages.ui.theme.backwardExit
import dev.hostyn.messages.ui.theme.forwardEnter
import dev.hostyn.messages.ui.theme.forwardExit
import dev.hostyn.messages.ui.theme.transformEnter
import dev.hostyn.messages.ui.theme.transformExit
import dev.hostyn.messages.ui.thread.ThreadScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/** Something another app asked us to open: a conversation, maybe with text or files to send. */
data class OpenRequest(
    val threadId: Long? = null,
    val addresses: List<String> = emptyList(),
    val text: String = "",
    val attachments: List<Uri> = emptyList(),
)

/** Text and files waiting to be put in a conversation's composer. */
data class Draft(val text: String, val attachments: List<Uri>)

class MainActivity : ComponentActivity() {
    private val request = mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        val app = application as MessagesApp
        setContent {
            MessagesTheme {
                SetupGate { AppNavigation(app, request) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val thread = intent.getLongExtra(Notifications.EXTRA_THREAD, -1)
        if (thread > 0) {
            request.value = OpenRequest(threadId = thread)
            return
        }
        val text = listOfNotNull(
            intent.getStringExtra("sms_body"),
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.data?.getQueryParameterSafe("body"),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val to = recipientsOf(intent.data)
                if (to.isNotEmpty() || text.isNotEmpty()) request.value = OpenRequest(addresses = to, text = text)
            }
            Intent.ACTION_SEND -> {
                val stream = intent.parcelable<Uri>(Intent.EXTRA_STREAM)
                request.value = OpenRequest(addresses = recipientsOf(intent.data), text = text, attachments = listOfNotNull(stream))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = intent.parcelableList<Uri>(Intent.EXTRA_STREAM)
                request.value = OpenRequest(text = text, attachments = streams)
            }
        }
    }
}

private fun Uri.getQueryParameterSafe(name: String): String? =
    // "smsto:123?body=Hi" is opaque, so getQueryParameter() doesn't work on it.
    schemeSpecificPart?.substringAfter('?', "")?.split('&')
        ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableList(key: String): List<T> =
    (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java) else getParcelableArrayListExtra(key)).orEmpty()

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppNavigation(app: MessagesApp, request: MutableState<OpenRequest?>) {
    val nav = rememberNavController()
    val drafts = remember { HashMap<Long, Draft>() }
    // Shared content with no recipient yet: it goes to whichever conversation is picked next.
    val pendingShare = remember { mutableStateOf<Draft?>(null) }

    LaunchedEffect(Unit) {
        // One request at a time; clearing it must not cancel the one being opened.
        snapshotFlow { request.value }.filterNotNull().collect { r ->
            request.value = null
            val draft = Draft(r.text, r.attachments).takeIf { it.text.isNotEmpty() || it.attachments.isNotEmpty() }
            val threadId = r.threadId ?: if (r.addresses.isNotEmpty()) {
                withContext(Dispatchers.IO) { runCatching { app.repository.threadIdFor(r.addresses) }.getOrNull() }
            } else null
            if (threadId != null) {
                if (draft != null) drafts[threadId] = draft
                nav.navigate("thread/$threadId") { popUpTo("list"); launchSingleTop = true }
            } else if (draft != null) {
                pendingShare.value = draft
                nav.navigate("new") { popUpTo("list") }
            }
        }
    }

    val density = LocalDensity.current
    val origin = remember { TransformOrigin() }
    /** [route], plus the element it grows out of if a row or button just asked for a container transform. */
    fun withOrigin(route: String): String {
        val key = origin.take() ?: return route
        return route + (if ('?' in route) "&" else "?") + "from=" + Uri.encode(key)
    }

    SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalTransformOrigin provides origin) {
    NavHost(
        navController = nav,
        startDestination = "list",
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        // M3 forward and backward pattern; screens opened with a container transform only fade
        // underneath it.
        enterTransition = { if (targetState.transformKey() != null) transformEnter() else density.forwardEnter() },
        exitTransition = { if (targetState.transformKey() != null) transformExit() else density.forwardExit() },
        popEnterTransition = { if (initialState.transformKey() != null) transformEnter() else density.backwardEnter() },
        popExitTransition = { if (initialState.transformKey() != null) transformExit() else density.backwardExit() },
    ) {
        screen("list") {
            ConversationsScreen(
                repo = app.repository,
                onOpen = { nav.navigate(withOrigin("thread/$it")) },
                onNewChat = { nav.navigate(withOrigin("new")) },
                onSettings = { nav.navigate("settings") },
            )
        }
        screen(
            "thread/{id}?from={from}",
            listOf(navArgument("id") { type = NavType.LongType }, fromArgument()),
        ) { entry ->
            val id = entry.arguments!!.getLong("id")
            val draft = remember(id) { drafts.remove(id) }
            ThreadScreen(
                threadId = id,
                initialText = draft?.text.orEmpty(),
                initialAttachments = draft?.attachments.orEmpty(),
                transformKey = entry.transformKey(),
                onBack = { if (!nav.popBackStack()) nav.navigate("list") },
            )
        }
        screen("new?from={from}", listOf(fromArgument())) { entry ->
            NewChatScreen(
                transformKey = entry.transformKey(),
                onBack = { pendingShare.value = null; nav.popBackStack() },
                onOpen = { id ->
                    pendingShare.value?.let { drafts[id] = it }
                    pendingShare.value = null
                    nav.navigate("thread/$id") { popUpTo("list") }
                },
            )
        }
        screen("settings") {
            SettingsScreen(settings = app.settings, onBack = { nav.popBackStack() })
        }
    }
    }
    }
}

private fun fromArgument() = navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null }

/** The element this screen grows out of (container transform), if it was opened from one. */
private fun NavBackStackEntry.transformKey(): String? = arguments?.getString("from")

/** A destination whose content can take part in container transforms. */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}
