package com.nestra.remote.viewer

import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nestra.remote.core.session.LiveSessionController.State
import com.nestra.remote.ui.AppViewModel
import com.nestra.remote.ui.UiState

/**
 * ETAP 9 live session screen. The picture is the native RustDesk core drawing the PC's REAL screen into a
 * SurfaceView (never screenshots). Input: one finger moves the remote cursor without holding a button; tap = left
 * click; long press without movement = right click. Dragging is explicit via the Drag toolbar mode. Two fingers = scroll
 * (or pan when zoomed), pinch = zoom on the phone, keyboard button = text + keys.
 * A red "REMOTE SESSION ACTIVE" bar with DISCONNECT is always visible; Back also disconnects.
 */
@Composable
fun ViewerScreen(s: UiState, vm: AppViewModel, pcName: String) {
    val st = s.session
    BackHandler { vm.disconnectSession("back") }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var keyboard by remember { mutableStateOf(false) }
    var dragMode by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFB00020)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (st is State.Active) "REMOTE SESSION ACTIVE · $pcName" else "Connecting to $pcName…",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (st is State.Active) {
                OutlinedButton(onClick = { dragMode = !dragMode }) { Text(if (dragMode) "Drag ON" else "Drag", color = Color.White) }
                OutlinedButton(onClick = { keyboard = !keyboard }, modifier = Modifier.padding(start = 6.dp)) { Text("Keyboard", color = Color.White) }
            }
            Button(onClick = { vm.disconnectSession("disconnect-button") }, colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.padding(start = 8.dp)) { Text("DISCONNECT", color = Color(0xFFB00020)) }
        }
        Box(Modifier.fillMaxSize().onSizeChanged { view = it }) {
            val viewer = vm.viewer
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        // Surface lifecycle only attaches / detaches the drawing target: it never ends the session
                        override fun surfaceCreated(h: SurfaceHolder) { ViewerLog.i("surfaceCreated"); vm.viewer?.setSurface(h.surface) }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { ViewerLog.i("surfaceChanged ${w}x$hh"); vm.viewer?.setSurface(h.surface) }
                        override fun surfaceDestroyed(h: SurfaceHolder) { ViewerLog.i("surfaceDestroyed (session stays open)"); vm.viewer?.setSurface(null) }
                    })
                } },
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .pointerInput(s.remoteWidth, s.remoteHeight) {
                        // view point -> remote PC pixel (letterboxed fit, then the local zoom/pan)
                        fun map(p: Offset): Pair<Int, Int> {
                            val rw = s.remoteWidth.toFloat(); val rh = s.remoteHeight.toFloat()
                            if (rw <= 0f || rh <= 0f || view.width == 0) return 0 to 0
                            val cx = view.width / 2f; val cy = view.height / 2f
                            val ux = (p.x - cx - offset.x) / scale + cx; val uy = (p.y - cy - offset.y) / scale + cy
                            val fit = minOf(view.width / rw, view.height / rh)
                            val ox = (view.width - rw * fit) / 2f; val oy = (view.height - rh * fit) / 2f
                            return (((ux - ox) / fit).coerceIn(0f, rw - 1)).toInt() to (((uy - oy) / fit).coerceIn(0f, rh - 1)).toInt()
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val t0 = down.uptimeMillis
                            val start = down.position
                            var last = start
                            var dragging = false
                            var multi = false
                            var tEnd = t0
                            do {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    multi = true
                                    val z = ev.calculateZoom(); val pan = ev.calculatePan()
                                    if (z != 1f) scale = (scale * z).coerceIn(1f, 4f)
                                    if (scale > 1.01f) offset += pan
                                    else if (pan.y != 0f) map(pressed[0].position).let { viewer?.mouse(NativeViewer.WHEEL, it.first, it.second, delta = if (pan.y > 0) 1 else -1) }
                                } else if (pressed.size == 1 && !multi) {
                                    val change = pressed[0]
                                    last = change.position
                                    tEnd = change.uptimeMillis
                                    val distance = (last - start).getDistance()

                                    // Normal one-finger movement is cursor hover only. It must never press LEFT.
                                    map(last).let { viewer?.mouse(NativeViewer.MOVE, it.first, it.second) }

                                    // Dragging is explicit only: the toolbar Drag mode must be enabled first.
                                    if (dragMode && !dragging && distance > viewConfiguration.touchSlop) {
                                        dragging = true
                                        map(start).let { viewer?.mouse(NativeViewer.DOWN, it.first, it.second, NativeViewer.LEFT) }
                                        map(last).let { viewer?.mouse(NativeViewer.MOVE, it.first, it.second) }
                                    }
                                }
                                ev.changes.firstOrNull()?.let { tEnd = it.uptimeMillis }
                                ev.changes.forEach { it.consume() }
                            } while (ev.changes.any { it.pressed })
                            if (multi) return@awaitEachGesture

                            val (x, y) = map(last)
                            val distance = (last - start).getDistance()
                            val duration = tEnd - t0
                            when {
                                dragging -> viewer?.mouse(NativeViewer.UP, x, y, NativeViewer.LEFT)
                                duration >= viewConfiguration.longPressTimeoutMillis && distance <= viewConfiguration.touchSlop -> {
                                    viewer?.mouse(NativeViewer.MOVE, x, y)
                                    viewer?.mouse(NativeViewer.DOWN, x, y, NativeViewer.RIGHT)
                                    viewer?.mouse(NativeViewer.UP, x, y, NativeViewer.RIGHT)
                                }
                                distance <= viewConfiguration.touchSlop -> {
                                    viewer?.mouse(NativeViewer.MOVE, x, y)
                                    viewer?.mouse(NativeViewer.DOWN, x, y, NativeViewer.LEFT)
                                    viewer?.mouse(NativeViewer.UP, x, y, NativeViewer.LEFT)
                                }
                                else -> Unit // cursor move only
                            }
                        }
                    },
            )
            if (st !is State.Active) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
                Text(when (st) {
                    State.Requesting -> "Asking the PC…"
                    State.WaitingForPc -> "Waiting for the PC to start the remote desktop engine…"
                    State.Connecting -> "Connecting to the PC screen…"
                    else -> ""
                }, color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
            if (keyboard && st is State.Active) KeyboardInput(vm, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Hidden text field: typed text goes to the PC as text; Backspace / Enter / arrows as keys. Nothing is stored. */
@Composable
private fun KeyboardInput(vm: AppViewModel, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val sentinel = " "
    var value by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(1))) }
    BasicTextField(
        value = value,
        onValueChange = { nv ->
            val v = vm.viewer
            when {
                nv.text.isEmpty() -> v?.let { it.key(KeyEvent.KEYCODE_DEL, true); it.key(KeyEvent.KEYCODE_DEL, false) }
                nv.text.length > 1 -> v?.let {
                    it.text(nv.text.substring(1).replace("\n", ""))
                    if (nv.text.contains('\n')) { it.key(KeyEvent.KEYCODE_ENTER, true); it.key(KeyEvent.KEYCODE_ENTER, false) }
                }
            }
            value = TextFieldValue(sentinel, TextRange(1))
        },
        modifier = modifier.size(1.dp).focusRequester(focus).onKeyEvent { e ->
            val n = e.nativeKeyEvent
            if (n.keyCode in listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB)) {
                vm.viewer?.key(n.keyCode, n.action == KeyEvent.ACTION_DOWN); true
            } else false
        },
    )
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus(); kb?.show() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { kb?.hide() } }
}
