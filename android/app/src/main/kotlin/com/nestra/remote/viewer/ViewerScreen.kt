package com.nestra.remote.viewer

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
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
 * The red session controls can be fully hidden on the phone; swipe down from the very top edge to restore them. Back also disconnects.
 */
@Composable
fun ViewerScreen(s: UiState, vm: AppViewModel, pcName: String) {
    val st = s.session
    BackHandler { vm.disconnectSession("back") }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var keyboard by remember { mutableStateOf(false) }
    var audioOn by remember { mutableStateOf(true) }
    var dragMode by remember { mutableStateOf(false) }
    var touchpadMode by remember { mutableStateOf(true) }
    var sessionBarVisible by remember { mutableStateOf(true) }
    var toolsVisible by remember { mutableStateOf(false) }
    var filesVisible by remember { mutableStateOf(false) }
    var currentDisplay by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.uploadUri(uri)
    }
    var cursorRemote by remember(s.remoteWidth, s.remoteHeight) {
        mutableStateOf(Offset((s.remoteWidth.coerceAtLeast(1) / 2f), (s.remoteHeight.coerceAtLeast(1) / 2f)))
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (sessionBarVisible) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFFB00020)).padding(horizontal = if (portrait) 8.dp else 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (st is State.Active) (if (portrait) "REMOTE ACTIVE" else "REMOTE SESSION ACTIVE · $pcName") else "Connecting…",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (portrait) 12.sp else 14.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (st is State.Active && !portrait) {
                    OutlinedButton(onClick = { touchpadMode = !touchpadMode }) { Text(if (touchpadMode) "Touchpad" else "Direct", color = Color.White) }
                    OutlinedButton(onClick = { dragMode = !dragMode }, modifier = Modifier.padding(start = 6.dp)) { Text(if (dragMode) "Drag ON" else "Drag", color = Color.White) }
                    OutlinedButton(onClick = { keyboard = !keyboard }, modifier = Modifier.padding(start = 6.dp)) { Text("Keyboard", color = Color.White) }
                }
                if (st is State.Active) {
                    OutlinedButton(onClick = { toolsVisible = !toolsVisible }, modifier = Modifier.padding(start = 6.dp)) { Text("Tools", color = Color.White) }
                    OutlinedButton(onClick = { sessionBarVisible = false; toolsVisible = false; filesVisible = false }, modifier = Modifier.padding(start = 6.dp)) { Text("Hide", color = Color.White) }
                }
                Button(
                    onClick = { vm.disconnectSession("disconnect-button") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.padding(start = 6.dp)
                ) { Text(if (portrait) "X" else "DISCONNECT", color = Color(0xFFB00020)) }
            }
        }
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { view = it }
                .pointerInput(sessionBarVisible) {
                    if (!sessionBarVisible) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val start = down.position
                            var last = start
                            do {
                                val ev = awaitPointerEvent()
                                ev.changes.firstOrNull { it.pressed }?.let { last = it.position }
                            } while (ev.changes.any { it.pressed })
                            // Hidden really means full-screen. Restore controls with a deliberate swipe down
                            // that starts at the very top edge, so there is no persistent on-screen banner/button.
                            if (start.y <= 80f && last.y - start.y > viewConfiguration.touchSlop * 3f) {
                                sessionBarVisible = true
                            }
                        }
                    }
                }
        ) {
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
                    .pointerInput(s.remoteWidth, s.remoteHeight, touchpadMode, dragMode) {
                        // Native rendering fills the SurfaceView, so map X/Y independently and then undo local zoom/pan.
                        fun map(p: Offset): Pair<Int, Int> {
                            val rw = s.remoteWidth.toFloat(); val rh = s.remoteHeight.toFloat()
                            if (rw <= 0f || rh <= 0f || view.width == 0) return 0 to 0
                            val cx = view.width / 2f; val cy = view.height / 2f
                            val ux = (p.x - cx - offset.x) / scale + cx; val uy = (p.y - cy - offset.y) / scale + cy
                            return ((ux / view.width * rw).coerceIn(0f, rw - 1)).toInt() to
                                ((uy / view.height * rh).coerceIn(0f, rh - 1)).toInt()
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val t0 = down.uptimeMillis
                            val start = down.position
                            var last = start
                            var prevFinger = start
                            val cursorStart = cursorRemote
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
                                    if (scale > 1.01f) {
                                        val maxX = view.width * (scale - 1f) / 2f
                                        val maxY = view.height * (scale - 1f) / 2f
                                        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                                    } else if (pan.y != 0f) {
                                        map(pressed[0].position).let { viewer?.mouse(NativeViewer.WHEEL, it.first, it.second, delta = if (pan.y > 0) 1 else -1) }
                                    }
                                } else if (pressed.size == 1 && !multi) {
                                    val change = pressed[0]
                                    last = change.position
                                    tEnd = change.uptimeMillis
                                    val distance = (last - start).getDistance()

                                    if (touchpadMode) {
                                        if (dragMode && !dragging && distance > viewConfiguration.touchSlop) {
                                            dragging = true
                                            viewer?.mouse(NativeViewer.DOWN, cursorStart.x.toInt(), cursorStart.y.toInt(), NativeViewer.LEFT)
                                        }
                                        val rw = s.remoteWidth.coerceAtLeast(1).toFloat()
                                        val rh = s.remoteHeight.coerceAtLeast(1).toFloat()
                                        val gain = maxOf(rw / view.width.coerceAtLeast(1), rh / view.height.coerceAtLeast(1)) * 1.15f
                                        val d = last - prevFinger
                                        cursorRemote = Offset(
                                            (cursorRemote.x + d.x * gain).coerceIn(0f, rw - 1f),
                                            (cursorRemote.y + d.y * gain).coerceIn(0f, rh - 1f)
                                        )
                                        prevFinger = last
                                        viewer?.mouse(NativeViewer.MOVE, cursorRemote.x.toInt(), cursorRemote.y.toInt())
                                    } else {
                                        map(last).let { viewer?.mouse(NativeViewer.MOVE, it.first, it.second) }
                                        if (dragMode && !dragging && distance > viewConfiguration.touchSlop) {
                                            dragging = true
                                            map(start).let { viewer?.mouse(NativeViewer.DOWN, it.first, it.second, NativeViewer.LEFT) }
                                            map(last).let { viewer?.mouse(NativeViewer.MOVE, it.first, it.second) }
                                        }
                                    }
                                }
                                ev.changes.firstOrNull()?.let { tEnd = it.uptimeMillis }
                                ev.changes.forEach { it.consume() }
                            } while (ev.changes.any { it.pressed })

                            if (multi) {
                                if (scale < 1.08f) { scale = 1f; offset = Offset.Zero }
                                return@awaitEachGesture
                            }

                            val target = if (touchpadMode) cursorRemote.x.toInt() to cursorRemote.y.toInt() else map(last)
                            val (x, y) = target
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
                                else -> Unit
                            }
                        }
                    },
            )
            if (st is State.Active && touchpadMode && s.remoteWidth > 0 && s.remoteHeight > 0 && view.width > 0 && view.height > 0) {
                val rw = s.remoteWidth.toFloat(); val rh = s.remoteHeight.toFloat()
                val cx = view.width / 2f; val cy = view.height / 2f
                val ux = cursorRemote.x / rw * view.width
                val uy = cursorRemote.y / rh * view.height
                val sx = (ux - cx) * scale + cx + offset.x
                val sy = (uy - cy) * scale + cy + offset.y
                Canvas(
                    modifier = Modifier
                        .offset { IntOffset(sx.toInt(), sy.toInt()) }
                        .size(28.dp)
                ) {
                    // Cursor hotspot is exactly the top-left point (0,0), matching the real Windows cursor hotspot.
                    val p = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(0f, size.height * 0.82f)
                        lineTo(size.width * 0.24f, size.height * 0.64f)
                        lineTo(size.width * 0.42f, size.height)
                        lineTo(size.width * 0.58f, size.height * 0.91f)
                        lineTo(size.width * 0.40f, size.height * 0.57f)
                        lineTo(size.width * 0.78f, size.height * 0.57f)
                        close()
                    }
                    drawPath(p, Color.Black)
                    val inset = 2.2f
                    val q = Path().apply {
                        moveTo(inset, inset)
                        lineTo(inset, size.height * 0.74f)
                        lineTo(size.width * 0.25f, size.height * 0.58f)
                        lineTo(size.width * 0.43f, size.height * 0.91f)
                        lineTo(size.width * 0.51f, size.height * 0.86f)
                        lineTo(size.width * 0.34f, size.height * 0.53f)
                        lineTo(size.width * 0.69f, size.height * 0.53f)
                        close()
                    }
                    drawPath(q, Color.White)
                }
            }
            if (toolsVisible && st is State.Active) {
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .widthIn(min = 190.dp, max = 260.dp)
                        .background(Color(0xEE16191D))
                        .padding(10.dp)
                ) {
                    Text("TOOLS", color = Color.White, fontWeight = FontWeight.Bold)
                    if (portrait) {
                        OutlinedButton(onClick = { touchpadMode = !touchpadMode }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(if (touchpadMode) "Touchpad" else "Direct", color = Color.White)
                        }
                        OutlinedButton(onClick = { dragMode = !dragMode }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(if (dragMode) "Drag ON" else "Drag", color = Color.White)
                        }
                        OutlinedButton(onClick = { keyboard = !keyboard }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text("Keyboard", color = Color.White)
                        }
                    }
                    OutlinedButton(onClick = { vm.viewer?.toggleAudio(); audioOn = !audioOn }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(if (audioOn) "Audio ON" else "Audio OFF", color = Color.White)
                    }
                    if (s.displayCount > 1) {
                        OutlinedButton(onClick = {
                            currentDisplay = (currentDisplay + 1) % s.displayCount
                            vm.viewer?.switchDisplay(currentDisplay)
                            scale = 1f; offset = Offset.Zero
                        }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Screen ${currentDisplay + 1}/${s.displayCount}", color = Color.White) }
                    }
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }?.let { vm.viewer?.sendClipboard(it) }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Send clipboard", color = Color.White) }
                    OutlinedButton(onClick = {
                        filesVisible = true; toolsVisible = false
                        if (s.remoteFiles.isEmpty()) vm.openRemoteFiles("")
                    }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Files", color = Color.White) }
                    OutlinedButton(onClick = { scale = 1f; offset = Offset.Zero }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Fit screen", color = Color.White) }
                    OutlinedButton(onClick = { toolsVisible = false }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Close", color = Color.White) }
                }
            }
            if (filesVisible && st is State.Active) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(min = 320.dp, max = 620.dp)
                        .heightIn(max = 430.dp)
                        .background(Color(0xEE16191D))
                        .padding(12.dp)
                ) {
                    Text("FILES · ${s.remotePath.ifBlank { "PC home" }}", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { vm.remoteFilesUp() }) { Text("Up", color = Color.White) }
                        OutlinedButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(start = 6.dp)) { Text("Upload", color = Color.White) }
                        OutlinedButton(onClick = { vm.openRemoteFiles(s.remotePath) }, modifier = Modifier.padding(start = 6.dp)) { Text("Refresh", color = Color.White) }
                        Box(Modifier.weight(1f))
                        OutlinedButton(onClick = { filesVisible = false }) { Text("Close", color = Color.White) }
                    }
                    s.fileStatus?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                        items(s.remoteFiles, key = { it.name + ":" + it.entryType }) { entry ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (entry.isDirectory || entry.isDrive) vm.openRemoteEntry(entry) else vm.downloadRemoteFile(entry)
                                }.padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (entry.isDirectory || entry.isDrive) "📁" else "📄", fontSize = 18.sp)
                                Text(entry.name, color = Color.White, modifier = Modifier.padding(start = 8.dp).weight(1f))
                                if (!(entry.isDirectory || entry.isDrive)) Text("${entry.size} B", color = Color.LightGray, fontSize = 11.sp)
                            }
                        }
                    }
                    Text("Tap a folder to open it. Tap a file to download it to NESTRA Remote/Downloads.", color = Color.LightGray, fontSize = 11.sp)
                }
            }
            if (st is State.Active && !keyboard && !filesVisible) {
                Button(
                    onClick = { keyboard = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xAA20242A)),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                ) { Text("⌨", color = Color.White, fontSize = 20.sp) }
            }
            if (st !is State.Active) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
                Text(when (st) {
                    State.Requesting -> "Asking the PC…"
                    State.WaitingForPc -> "Waiting for the PC to start the remote desktop engine…"
                    State.Connecting -> "Connecting to the PC screen…"
                    else -> ""
                }, color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
            if (keyboard && st is State.Active) KeyboardInput(
                vm = vm,
                onClose = { keyboard = false },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** Hidden text field: typed text goes to the PC as text; Backspace / Enter / arrows as keys. Nothing is stored. */
@Composable
private fun KeyboardInput(vm: AppViewModel, onClose: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val sentinel = " "
    var value by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(1))) }

    Box(modifier.fillMaxWidth()) {
        // Always-visible close control sits immediately above the Android IME, so closing the keyboard never
        // requires reopening the red session toolbar.
        Button(
            onClick = { kb?.hide(); onClose() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xDD20242A)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        ) { Text("✕ Keyboard", color = Color.White) }

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
            modifier = Modifier.size(1.dp).align(Alignment.BottomStart).focusRequester(focus).onKeyEvent { e ->
                val n = e.nativeKeyEvent
                if (n.keyCode in listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB)) {
                    vm.viewer?.key(n.keyCode, n.action == KeyEvent.ACTION_DOWN); true
                } else false
            },
        )
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus(); kb?.show() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { kb?.hide() } }
}
