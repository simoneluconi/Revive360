package com.simoneluconi.revive360.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.Revive360App
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.CompanionWifi
import com.simoneluconi.revive360.ui.theme.AppTheme
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

/** The app's screens; the back stack is a plain list. */
sealed interface Screen {
    data object Home : Screen
    /** Pairs a new camera, or sets up [replaceId] again. */
    data class Pair(val replaceId: String? = null) : Screen
    data object Gallery : Screen
    data class File(val path: String) : Screen
    data object Settings : Screen
    data object Log : Screen
}

/** What screens need from the activity: navigation, short messages, system prompts. */
class AppActions(
    val app: Revive360App,
    val navigate: (Screen) -> Unit,
    val back: () -> Unit,
    /** Back to the home screen, dropping everything above it. */
    val home: () -> Unit,
    val message: (String, MessageKind) -> Unit,
    /** The system's chooser for the camera's Wi-Fi (see MainActivity.findCamera). */
    val findCamera: (onResult: (CompanionWifi.Found?) -> Unit) -> Unit,
)

enum class MessageKind { INFO, GOOD, BAD }

val LocalActions = staticCompositionLocalOf<AppActions> { error("No AppActions") }

private data class Message(val text: String, val kind: MessageKind, val id: Long = System.nanoTime())

/** One screen in the back stack; [id] tells two visits of the same screen apart. */
private data class Entry(val id: Int, val screen: Screen)

@Composable
fun AppRoot(app: Revive360App, findCamera: ((CompanionWifi.Found?) -> Unit) -> Unit) {
    val theme by app.settings.theme.collectAsStateWithLifecycle()
    val accent by app.settings.accent.collectAsStateWithLifecycle()
    val haptics by app.settings.haptics.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf(Entry(0, Screen.Home)) }
    var nextId by remember { mutableIntStateOf(1) }
    var forward by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<Message?>(null) }
    // Each entry keeps its saveable state (scroll positions...) while it is in the stack
    val saved = rememberSaveableStateHolder()

    val actions = remember(app) {
        AppActions(
            app = app,
            navigate = {
                forward = true
                stack.add(Entry(nextId++, it))
            },
            back = {
                if (stack.size > 1) {
                    forward = false
                    saved.removeState(stack.removeAt(stack.lastIndex).id)
                }
            },
            home = {
                forward = false
                while (stack.size > 1) saved.removeState(stack.removeAt(stack.lastIndex).id)
            },
            message = { text, kind -> message = Message(text, kind) },
            findCamera = findCamera,
        )
    }

    AppTheme(theme, accent) {
        CompositionLocalProvider(LocalActions provides actions, LocalHaptics provides haptics) {
            val colors = LocalAppColors.current
            BackHandler(enabled = stack.size > 1) { actions.back() }
            Box(Modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
                AnimatedContent(
                    targetState = stack.last(),
                    transitionSpec = {
                        if (forward) {
                            (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 8 } + fadeOut())
                        } else {
                            (slideInHorizontally { -it / 8 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                        }
                    },
                    label = "screen",
                ) { entry ->
                    saved.SaveableStateProvider(entry.id) {
                        when (val screen = entry.screen) {
                            Screen.Home -> HomeScreen()
                            is Screen.Pair -> PairScreen(screen.replaceId)
                            Screen.Gallery -> GalleryScreen()
                            is Screen.File -> FileScreen(screen.path)
                            Screen.Settings -> SettingsScreen()
                            Screen.Log -> LogScreen()
                        }
                    }
                }
                MessageBanner(message, Modifier.align(Alignment.BottomCenter)) { message = null }
            }
        }
    }
}

@Composable
private fun MessageBanner(message: Message?, modifier: Modifier, onGone: () -> Unit) {
    val colors = LocalAppColors.current
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(2600)
            onGone()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier.padding(16.dp),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val shown = remember(message?.id) { message } ?: return@AnimatedVisibility
        val tint = when (shown.kind) {
            MessageKind.INFO -> colors.text
            MessageKind.GOOD -> colors.good
            MessageKind.BAD -> colors.bad
        }
        Row(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.text)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (shown.kind == MessageKind.INFO) colors.accent else tint)
            Spacer(Modifier.width(12.dp))
            Label(shown.text, color = colors.bg, weight = FontWeight.Bold)
        }
    }
}

/** A screen with a back button, a title and scrolling content. */
@Composable
fun ScreenScaffold(
    title: String,
    showBack: Boolean = true,
    scroll: Boolean = true,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalActions.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                RoundIconButton(AppIcons.Back, label = stringResource(R.string.back), onClick = nav.back)
                Spacer(Modifier.width(10.dp))
            }
            Label(title, Modifier.weight(1f).padding(start = if (showBack) 0.dp else 6.dp), size = 24.sp,
                weight = FontWeight.ExtraBold, maxLines = 1)
            actions()
        }
        Column(
            Modifier
                .fillMaxSize()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 16.dp)
                .padding(bottom = if (scroll) 32.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
