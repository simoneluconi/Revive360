package com.simoneluconi.revive360.ui

import android.graphics.SurfaceTexture
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.CameraController
import com.simoneluconi.revive360.media.LiveViewDecoder
import com.simoneluconi.revive360.protocol.LiveStream
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** The camera's live picture (protocol §5.14), as it streams it: both lenses side by side. */
@Composable
fun LiveViewScreen() {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val resources = LocalResources.current
    var size by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var frames by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val session = remember { LiveSession(nav.app.controller) }
    // The retry the view already acted on (update runs at every recomposition)
    val handled = remember { intArrayOf(0) }
    var full by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current

    DisposableEffect(Unit) { onDispose { session.stop() } }
    BackHandler(enabled = full) { full = false }
    // Full screen hides the system bars; leaving the screen shows them again
    DisposableEffect(full) {
        systemBars(view, show = !full)
        onDispose { systemBars(view, show = true) }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            delay(1_000)
            fps = (frames - last).toInt()
            last = frames
        }
    }

    // The picture keeps its place in the composition in both layouts: its surface (and the
    // stream) survive the switch
    ScreenScaffold(title = stringResource(R.string.liveview_title), scroll = false, bare = full) {
        val ratio = size?.let { (w, h) -> w.toFloat() / h } ?: 2f
        Box(
            (if (full) Modifier.fillMaxSize().background(Color.Black) else Modifier.fillMaxWidth())
                .pointerInput(Unit) { detectTapGestures { full = !full } },
            contentAlignment = Alignment.Center,
        ) {
          Box(
            Modifier.aspectRatio(ratio).clip(RoundedCornerShape(if (full) 0.dp else 20.dp)).background(colors.surface2),
            contentAlignment = Alignment.Center,
          ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                session.start(
                                    Surface(texture),
                                    onSize = { w, h -> size = w to h },
                                    onFrame = { frames = it },
                                    onError = { error = it },
                                )
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                // The decoder must be off the surface before it goes
                                session.stop()
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                        }
                    }
                },
                update = { view ->
                    // A retry: a new session on the same surface
                    if (attempt > handled[0] && !session.running && view.isAvailable) {
                        handled[0] = attempt
                        error = null
                        session.start(
                            Surface(view.surfaceTexture),
                            onSize = { w, h -> size = w to h },
                            onFrame = { frames = it },
                            onError = { error = it },
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (frames == 0L && error == null) Spinner()
          }
        }
        if (full) return@ScreenScaffold
        val problem = error
        if (problem != null) {
            Card(color = colors.soft(colors.bad)) {
                Label(resources.getString(R.string.liveview_failed, problem), size = 14.sp)
                Spacer(Modifier.height(10.dp))
                PillButton(stringResource(R.string.action_retry), icon = AppIcons.Refresh) { attempt++ }
            }
        } else {
            Label(
                when {
                    frames == 0L -> stringResource(R.string.liveview_connecting)
                    else -> size?.let { (w, h) -> stringResource(R.string.liveview_info, w, h, fps) } ?: ""
                },
                Modifier.fillMaxWidth(), size = 13.sp, color = colors.text2, align = TextAlign.Center,
            )
        }
        Label(stringResource(R.string.liveview_hint_full), Modifier.fillMaxWidth(), size = 13.sp, color = colors.text2,
            align = TextAlign.Center)
        Label(stringResource(R.string.liveview_hint), Modifier.fillMaxWidth(), size = 13.sp, color = colors.text2,
            align = TextAlign.Center)
    }
}

/** Shows or hides the status and navigation bars (swipe brings them back for a moment) */
@Suppress("DEPRECATION")
private fun systemBars(view: View, show: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = view.windowInsetsController ?: return
        if (show) {
            controller.show(WindowInsets.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        }
    } else {
        view.systemUiVisibility = if (show) View.SYSTEM_UI_FLAG_VISIBLE else
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}

/**
 * One live view at a time: a thread reads the stream and decodes it onto the surface. Stopping
 * closes the stream (the camera then ends its own) and waits for the decoder to let go.
 */
private class LiveSession(private val controller: CameraController) {
    private val stream = AtomicReference<LiveStream?>(null)
    private var worker: Thread? = null
    private var surface: Surface? = null
    @Volatile private var stopping = false

    val running: Boolean get() = worker?.isAlive == true

    fun start(target: Surface, onSize: (Int, Int) -> Unit, onFrame: (Long) -> Unit, onError: (String) -> Unit) {
        if (running) return
        stopping = false
        surface?.takeIf { it !== target }?.release()
        surface = target
        worker = thread(name = "liveview") {
            try {
                val opened = controller.openLiveView()
                stream.set(opened)
                if (stopping) return@thread opened.close()
                EventLog.log("Live view: ${opened.contentType}")
                LiveViewDecoder(opened.input, target, onSize, onFrame).run()
                if (!stopping) onError("the camera ended the stream")
            } catch (e: Exception) {
                if (!stopping) {
                    EventLog.log("Live view failed: ${e.message}")
                    onError(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                stream.getAndSet(null)?.close()
            }
        }
    }

    fun stop() {
        stopping = true
        stream.getAndSet(null)?.close()
        worker?.join(STOP_WAIT_MS)
        worker = null
        surface?.release()
        surface = null
    }

    private companion object {
        const val STOP_WAIT_MS = 2_000L
    }
}
