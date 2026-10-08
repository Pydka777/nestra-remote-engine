package com.nestra.remote.viewer

import android.util.Log
import com.nestra.remote.core.session.Redact

/**
 * v0.2.1 physical-device diagnostics: `adb logcat -s NESTRA-VIEWER`. Every line passes [Redact.line] (IDs shortened,
 * token-like runs redacted); the grant, account tokens, the Parent session and key material are never handed to it.
 * The native core (libnestra_viewer.so) writes to the same tag.
 */
object ViewerLog {
    const val TAG = "NESTRA-VIEWER"
    fun i(msg: String) { Log.i(TAG, Redact.line(msg)) }
    fun w(msg: String) { Log.w(TAG, Redact.line(msg)) }

    /** The app frames that led here (class.method:line), so a log line shows WHO closed a session. */
    fun caller(): String = Throwable().stackTrace.drop(1)
        .filter { it.className.startsWith("com.nestra") && !it.className.endsWith("ViewerLog") }
        .take(5).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
}
