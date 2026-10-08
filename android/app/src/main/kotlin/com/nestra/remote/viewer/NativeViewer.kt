package com.nestra.remote.viewer

import android.view.Surface
import com.nestra.remote.core.session.RemoteViewer
import com.nestra.remote.core.session.SecretDiag
import com.nestra.remote.core.session.ViewerTarget
import java.util.concurrent.atomic.AtomicLong

/**
 * JNI bridge to libnestra_viewer.so: the upstream RustDesk client core (librustdesk, AGPL-3.0) with the NESTRA viewer
 * entry points (src/nestra_viewer.rs), built by the public nestra-remote-engine repo (workflow android-viewer) from a
 * pinned upstream commit. It decodes the PC's real screen into [RustDeskViewer.setSurface] and sends input.
 * When the library is not in the APK, [available] is false and the app offers NO session: there is no fallback
 * picture, no screenshots, nothing that pretends to be the screen.
 */
object NativeViewer {
    const val ABI = 3
    val available: Boolean = try { System.loadLibrary("nestra_viewer"); nativeAbiVersion() == ABI } catch (e: UnsatisfiedLinkError) { false }

    interface Callback {
        fun onConnected(width: Int, height: Int)
        fun onResolution(width: Int, height: Int)
        fun onDisplays(count: Int)
        fun onClipboard(text: String)
        fun onFileEvent(name: String, json: String)
        fun onClosed(reason: String)
    }

    @JvmStatic external fun nativeAbiVersion(): Int
    /** Once per process: upstream main_init(appDir) + the NESTRA fixed server/key settings. */
    @JvmStatic external fun nativeInit(appDir: String)
    @JvmStatic external fun nativeConnect(engineId: String, host: String, key: String, grant: CharArray, cb: Callback): Long
    @JvmStatic external fun nativeSetSurface(handle: Long, surface: Surface?)
    @JvmStatic external fun nativeMouse(handle: Long, kind: Int, x: Int, y: Int, button: Int, delta: Int)
    @JvmStatic external fun nativeKey(handle: Long, androidKeyCode: Int, down: Boolean)
    @JvmStatic external fun nativeText(handle: Long, text: String)
    @JvmStatic external fun nativeSwitchDisplay(handle: Long, display: Int)
    @JvmStatic external fun nativeSendClipboard(handle: Long, text: String)
    @JvmStatic external fun nativeToggleAudio(handle: Long)
    @JvmStatic external fun nativeReadRemoteDir(handle: Long, path: String)
    @JvmStatic external fun nativeTransferFile(handle: Long, from: String, to: String, remoteToLocal: Boolean): Int
    @JvmStatic external fun nativeClose(handle: Long)

    const val MOVE = 0; const val DOWN = 1; const val UP = 2; const val WHEEL = 3
    const val LEFT = 1; const val RIGHT = 2
}

/** RemoteViewer (core contract) implemented by the native RustDesk core. One instance per session. */
class RustDeskViewer(
    private val appDir: String,
    private val onSize: (Int, Int) -> Unit,
    private val onDisplays: (Int) -> Unit,
    private val onClipboard: (String) -> Unit,
    private val onFileEvent: (String, String) -> Unit,
) : RemoteViewer {
    private val handle = AtomicLong(0)
    @Volatile private var surface: Surface? = null

    override fun connect(target: ViewerTarget, events: RemoteViewer.Events) {
        check(NativeViewer.available) { "native viewer missing" }
        NativeViewer.nativeInit(appDir)
        ViewerLog.i("nativeConnect: $target (grant not logged), surface ${if (surface != null) "ready" else "not yet"}")
        ViewerLog.i(SecretDiag.describe("android_jni_call", target.grant()))
        val h = NativeViewer.nativeConnect(target.engineId, target.rendezvousHost, target.serverKey, target.grant(), object : NativeViewer.Callback {
            override fun onConnected(width: Int, height: Int) { ViewerLog.i("callback onConnected ${width}x$height"); onSize(width, height); events.onConnected() }
            override fun onResolution(width: Int, height: Int) { ViewerLog.i("callback onResolution ${width}x$height"); onSize(width, height) }
            override fun onDisplays(count: Int) { ViewerLog.i("callback onDisplays count=$count"); onDisplays(count.coerceAtLeast(1)) }
            override fun onClipboard(text: String) { ViewerLog.i("callback onClipboard ${text.toByteArray().size} bytes"); onClipboard(text) }
            override fun onFileEvent(name: String, json: String) { ViewerLog.i("callback onFileEvent $name"); onFileEvent(name, json) }
            override fun onClosed(reason: String) { ViewerLog.w("callback onClosed($reason) from the native core"); events.onClosed(reason) }
        })
        ViewerLog.i(if (h != 0L) "nativeConnect ok (generation ${h and 0xFFFFL})" else "nativeConnect REFUSED (see the native line before)")
        check(h != 0L) { "native viewer refused the target" }
        handle.set(h)
        surface?.let { NativeViewer.nativeSetSurface(h, it) }
    }

    /** Ends the native session. Only ever called by the LiveSessionController (DISCONNECT / Back / server end). */
    override fun disconnect() {
        val h = handle.getAndSet(0)
        ViewerLog.w("viewer.disconnect -> nativeClose (handle ${if (h != 0L) "open" else "already closed"}) called from ${ViewerLog.caller()}")
        if (h != 0L) NativeViewer.nativeClose(h)
    }

    /** A Surface going away (SurfaceView recreated, app in background, rotation) only pauses drawing: never a disconnect. */
    fun setSurface(s: Surface?) {
        surface = s
        ViewerLog.i("setSurface(${if (s != null) "attached" else "detached"}) session ${if (handle.get() != 0L) "open" else "not connected yet"}")
        handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeSetSurface(it, s) }
    }
    fun mouse(kind: Int, x: Int, y: Int, button: Int = NativeViewer.LEFT, delta: Int = 0) {
        handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeMouse(it, kind, x, y, button, delta) }
    }
    fun key(code: Int, down: Boolean) { handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeKey(it, code, down) } }
    fun text(t: String) { if (t.isNotEmpty()) handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeText(it, t) } }
    fun switchDisplay(display: Int) { handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeSwitchDisplay(it, display) } }
    fun sendClipboard(text: String) { if (text.isNotEmpty()) handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeSendClipboard(it, text) } }
    fun toggleAudio() { handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeToggleAudio(it) } }
    fun readRemoteDir(path: String) { handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeReadRemoteDir(it, path) } }
    fun transferFile(from: String, to: String, remoteToLocal: Boolean): Int = handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeTransferFile(it, from, to, remoteToLocal) } ?: -1
}
