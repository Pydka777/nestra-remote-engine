package com.nestra.remote.viewer

import android.view.Surface
import com.nestra.remote.core.session.RemoteViewer
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
    const val ABI = 2
    val available: Boolean = try { System.loadLibrary("nestra_viewer"); nativeAbiVersion() == ABI } catch (e: UnsatisfiedLinkError) { false }

    interface Callback {
        fun onConnected(width: Int, height: Int)
        fun onResolution(width: Int, height: Int)
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
    @JvmStatic external fun nativeClose(handle: Long)

    const val MOVE = 0; const val DOWN = 1; const val UP = 2; const val WHEEL = 3
    const val LEFT = 1; const val RIGHT = 2
}

/** RemoteViewer (core contract) implemented by the native RustDesk core. One instance per session. */
class RustDeskViewer(private val appDir: String, private val onSize: (Int, Int) -> Unit) : RemoteViewer {
    private val handle = AtomicLong(0)
    @Volatile private var surface: Surface? = null

    override fun connect(target: ViewerTarget, events: RemoteViewer.Events) {
        check(NativeViewer.available) { "native viewer missing" }
        NativeViewer.nativeInit(appDir)
        val h = NativeViewer.nativeConnect(target.engineId, target.rendezvousHost, target.serverKey, target.grant(), object : NativeViewer.Callback {
            override fun onConnected(width: Int, height: Int) { onSize(width, height); events.onConnected() }
            override fun onResolution(width: Int, height: Int) = onSize(width, height)
            override fun onClosed(reason: String) = events.onClosed(reason)
        })
        check(h != 0L) { "native viewer refused the target" }
        handle.set(h)
        surface?.let { NativeViewer.nativeSetSurface(h, it) }
    }

    override fun disconnect() {
        val h = handle.getAndSet(0)
        if (h != 0L) NativeViewer.nativeClose(h)
    }

    fun setSurface(s: Surface?) { surface = s; handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeSetSurface(it, s) } }
    fun mouse(kind: Int, x: Int, y: Int, button: Int = NativeViewer.LEFT, delta: Int = 0) {
        handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeMouse(it, kind, x, y, button, delta) }
    }
    fun key(code: Int, down: Boolean) { handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeKey(it, code, down) } }
    fun text(t: String) { if (t.isNotEmpty()) handle.get().takeIf { it != 0L }?.let { NativeViewer.nativeText(it, t) } }
}
