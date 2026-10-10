package com.nestra.remote.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nestra.remote.NestraRemoteApp
import com.nestra.remote.core.api.ApiResult
import com.nestra.remote.core.api.PairingPreview
import com.nestra.remote.core.api.RemoteDevice
import com.nestra.remote.core.pairing.PairingInput
import com.nestra.remote.core.session.LiveSessionController
import com.nestra.remote.core.session.LiveSessionText
import com.nestra.remote.core.session.RemoteSession
import com.nestra.remote.core.session.FileEvent
import com.nestra.remote.core.session.RemoteEntry
import com.nestra.remote.core.session.RemoteFiles
import com.nestra.remote.viewer.NativeViewer
import com.nestra.remote.viewer.RustDeskViewer
import com.nestra.remote.viewer.ViewerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

typealias RemoteFileEntry = RemoteEntry

/** The running file transfer as the panel shows it (one at a time). */
data class FileTransferUi(val id: Int, val name: String, val upload: Boolean, val percent: Int, val text: String)

sealed interface Screen {
    data object Splash : Screen
    data object SignIn : Screen
    data object Mfa : Screen
    data object Devices : Screen
    data object Pair : Screen
    data class DeviceDetails(val deviceId: String) : Screen
    data object Settings : Screen
    data class Session(val deviceId: String) : Screen
}

data class UiState(
    val screen: Screen = Screen.Splash,
    val busy: Boolean = false,
    val message: String? = null,
    val accountId: Long? = null,
    val devices: List<RemoteDevice> = emptyList(),
    val devicesLoaded: Boolean = false,
    val pairingId: String? = null,            // from the laptop's QR / nestra://pair link (identifies the session only)
    val preview: PairingPreview? = null,
    val pairedDeviceId: String? = null,
    // ETAP 9 live session (no grant, no credential: only the visible state)
    val session: LiveSessionController.State? = null,
    val remoteWidth: Int = 0,
    val remoteHeight: Int = 0,
    val displayCount: Int = 1,
    val remotePath: String = "",
    val remoteFiles: List<RemoteFileEntry> = emptyList(),
    val fileStatus: String? = null,
    val transfer: FileTransferUi? = null,
)

/**
 * UI state only. Every credential lives in RemoteSession (:core). Typed secrets (password, MFA code, pairing code) are
 * passed straight through and not kept in this state.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val session: RemoteSession = (app as NestraRemoteApp).session
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val r = io { session.restore() }
            if (r is RemoteSession.Step.SignedIn) signedIn(r.accountId) else go(Screen.SignIn)
        }
    }

    // ------------------------------------------------------------------ auth
    fun signIn(email: String, password: CharArray) = work {
        when (val r = io { session.signIn(email, password) }) {
            is RemoteSession.Step.SignedIn -> signedIn(r.accountId)
            RemoteSession.Step.MfaRequired -> go(Screen.Mfa)
            is RemoteSession.Step.Failed -> fail(r.failure)
        }
    }

    fun submitMfa(code: String) = work {
        when (val r = io { session.submitMfa(code) }) {
            is RemoteSession.Step.SignedIn -> signedIn(r.accountId)
            RemoteSession.Step.MfaRequired -> go(Screen.Mfa)
            is RemoteSession.Step.Failed -> {
                fail(r.failure)
                if (session.state is RemoteSession.State.SignedOut) go(Screen.SignIn, keepMessage = true)
            }
        }
    }

    fun cancelMfa() { session.cancelMfa(); go(Screen.SignIn) }

    fun logout() = work {
        reconnectCancelled = true
        controller?.disconnect("sign-out")
        io { session.logout() }
        _state.value = UiState(screen = Screen.SignIn, message = "Signed out. This phone holds no NESTRA credentials now.")
    }

    // ------------------------------------------------------------------ devices
    fun refreshDevices() = work { loadDevices() }

    fun openDevice(id: String) = go(Screen.DeviceDetails(id))
    fun openSettings() = go(Screen.Settings)
    fun back() = go(if (_state.value.accountId != null) Screen.Devices else Screen.SignIn)

    fun unpair(deviceId: String) = work {
        when (val r = io { session.unpair(deviceId) }) {
            is ApiResult.Ok -> { loadDevices(); go(Screen.Devices); say("Device removed from your account. Remote Access is OFF.") }
            else -> handle(r)
        }
    }

    // ------------------------------------------------------------------ pairing (ETAP 6 protocol, unchanged)
    fun openPair(pairingId: String? = null) {
        _state.update { it.copy(screen = Screen.Pair, pairingId = pairingId ?: it.pairingId, preview = null, pairedDeviceId = null, message = null) }
    }

    /** From MainActivity (deep link) or the QR scanner. Opens pairing after sign-in if needed. */
    fun pairingLink(pairingId: String) {
        _state.update { it.copy(pairingId = pairingId) }
        if (_state.value.accountId != null) openPair(pairingId)
    }

    fun clearPairingId() = _state.update { it.copy(pairingId = null, preview = null) }

    fun preview(code: String) = work {
        val input = PairingInput.of(code, _state.value.pairingId) ?: return@work say("Enter the 6-digit code shown on your PC.")
        when (val r = io { session.previewPairing(input) }) {
            is ApiResult.Ok -> _state.update { it.copy(preview = r.value) }
            else -> handle(r)
        }
    }

    fun completePairing(code: String) = work {
        val input = PairingInput.of(code, _state.value.pairingId) ?: return@work say("Enter the 6-digit code shown on your PC.")
        when (val r = io { session.completePairing(input) }) {
            is ApiResult.Ok -> {
                _state.update { it.copy(pairedDeviceId = r.value.deviceId, preview = null, pairingId = null) }
                loadDevices()
                say("${r.value.deviceName} is now paired with your account. Remote Access stays OFF until you turn it on on the PC.")
            }
            else -> handle(r)
        }
    }

    // ------------------------------------------------------------------ ETAP 9 live session
    @Volatile var viewer: RustDeskViewer? = null; private set
    @Volatile private var controller: LiveSessionController? = null
    @Volatile private var reconnectCancelled = false
    @Volatile private var connectLoopActive = false

    /** Connect: only for an own, paired, online PC whose owner switched Remote Access ON at the PC. */
    fun connect(deviceId: String) {
        if (controller != null || connectLoopActive) return
        if (!NativeViewer.available) {
            say("This app build does not contain the remote desktop viewer (RustDesk core). Install the NESTRA Remote APK built with the viewer.")
            return
        }
        val app = getApplication<Application>()
        fun newViewer() = RustDeskViewer(
            app.filesDir.absolutePath,
            onSizeChanged = { w, h -> _state.update { it.copy(remoteWidth = w, remoteHeight = h) } },
            onDisplaysChanged = { count -> _state.update { it.copy(displayCount = count.coerceAtLeast(1)) } },
            onClipboardReceived = { text ->
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("NESTRA Remote", text))
            },
            onFileEventReceived = ::handleFileEvent,
        )
        fun transient(end: LiveSessionController.State, attempt: Int): Boolean = when (end) {
            is LiveSessionController.State.Ended -> end.reason in setOf("connection_error", "engine_closed", "engine_exited", "viewer_failed")
            is LiveSessionController.State.Refused ->
                end.why == LiveSessionController.Refusal.NETWORK ||
                    end.why == LiveSessionController.Refusal.DEVICE_OFFLINE ||
                    (attempt > 0 && end.why == LiveSessionController.Refusal.SESSION_IN_PROGRESS)
            else -> false
        }

        reconnectCancelled = false
        connectLoopActive = true
        ViewerLog.i("Connect tapped (app ${com.nestra.remote.BuildConfig.VERSION_NAME}); native viewer ABI ${NativeViewer.ABI}")
        transfers.clear()
        _state.update { it.copy(screen = Screen.Session(deviceId), session = LiveSessionController.State.Requesting, message = null, remoteWidth = 0, remoteHeight = 0, displayCount = 1, remotePath = "", remoteFiles = emptyList(), fileStatus = null, transfer = null) }
        viewModelScope.launch(Dispatchers.IO) {
            var end: LiveSessionController.State
            var reconnectAttempt = 0
            while (true) {
                val v = newViewer()
                val c = LiveSessionController(session, v, diag = ViewerLog::i)
                viewer = v; controller = c
                c.listener = { st -> _state.update { it.copy(session = st) } }
                end = c.run(deviceId)
                ViewerLog.i("session finished: $end")
                controller = null; viewer = null
                // The server keeps a silent active viewer session for up to 120 s. If the phone loses the network
                // before it can POST /end, a fresh request may therefore see SESSION_IN_PROGRESS after connectivity
                // returns. Keep bounded retries alive for >120 s so Wi-Fi <-> LTE handoff can recover without the
                // user manually waiting for the stale server lease to expire.
                if (!transient(end, reconnectAttempt) || reconnectAttempt >= 20 || reconnectCancelled) break
                reconnectAttempt++
                val waitMs = (1_500L + reconnectAttempt * 1_000L).coerceAtMost(8_000L)
                ViewerLog.w("transient session end -> reconnect $reconnectAttempt/20 in ${waitMs}ms")
                _state.update { it.copy(session = LiveSessionController.State.Requesting, remoteWidth = 0, remoteHeight = 0, displayCount = 1) }
                delay(waitMs)
            }
            withContext(Dispatchers.Main) {
                controller = null; viewer = null; connectLoopActive = false
                // a session end also ends its file connection: unfinished transfers are dropped (partial files removed)
                transfers.values.forEach { it.local.delete() }; transfers.clear()
                _state.update { it.copy(session = null, screen = Screen.DeviceDetails(deviceId), remoteWidth = 0, remoteHeight = 0, displayCount = 1, remotePath = "", remoteFiles = emptyList(), fileStatus = null, transfer = null) }
                say(when (end) {
                    is LiveSessionController.State.Refused -> LiveSessionText.refusal(end.why, end.detail)
                    is LiveSessionController.State.Ended -> endText(end.reason)
                    else -> "Session ended."
                })
                loadDevices()
            }
        }
    }

    // ------------------------------------------------------------------ v0.4.0 file browser + transfer
    // Every file operation runs on the native FILE_TRANSFER connection (nestra_files.rs). Logs carry event names and
    // job ids only: never file names, paths or contents.

    private class PendingTransfer(val name: String, val upload: Boolean, val local: File)
    private val transfers = java.util.concurrent.ConcurrentHashMap<Int, PendingTransfer>()
    private var fileListRequestId = 0
    private val loadingText = "Loading files…"

    private fun handleFileEvent(name: String, json: String) {
        when (val ev = RemoteFiles.parse(name, json)) {
            is FileEvent.Dir -> {
                fileListRequestId++   // answered: no timeout message
                _state.update { it.copy(remotePath = ev.listing.path, remoteFiles = ev.listing.entries, fileStatus = if (ev.listing.entries.isEmpty()) "This folder is empty." else if (it.transfer != null) it.fileStatus else null) }
            }
            is FileEvent.Error -> {
                ViewerLog.w("file_error op=${ev.op}")
                if (ev.op != "transfer") fileListRequestId++
                val text = when (ev.op) {
                    "dir" -> "Could not open this folder: ${ev.message}"
                    "session" -> "File connection to the PC failed: ${ev.message}. Tap Refresh to retry."
                    else -> "Transfer could not start: ${ev.message}"
                }
                _state.update { it.copy(fileStatus = text) }
            }
            is FileEvent.Progress -> {
                val t = transfers[ev.id] ?: return
                val text = RemoteFiles.progressText(if (t.upload) "Uploading" else "Downloading", t.name, ev)
                _state.update { it.copy(transfer = FileTransferUi(ev.id, t.name, t.upload, ev.percent, text), fileStatus = null) }
            }
            is FileEvent.Done -> {
                val t = transfers.remove(ev.id) ?: return
                ViewerLog.i("file job ${ev.id} done (${if (t.upload) "upload" else "download"})")
                viewModelScope.launch(Dispatchers.IO) {
                    val text = if (t.upload) {
                        t.local.delete()                                  // the private cache copy
                        "Uploaded ${t.name} to ${RemoteFiles.title(_state.value.remotePath)}."
                    } else {
                        val where = exportToDownloads(t.local, t.name)
                        if (where != null) "Downloaded ${t.name} to $where." else "Downloaded ${t.name} to NESTRA Remote's app folder (${t.local.parentFile?.absolutePath})."
                    }
                    _state.update { it.copy(transfer = null, fileStatus = text) }
                    if (t.upload) withContext(Dispatchers.Main) { refreshListing() }
                }
            }
            is FileEvent.JobError -> {
                val t = transfers.remove(ev.id) ?: return
                ViewerLog.w("file job ${ev.id} ended: ${if (ev.cancelled) "cancelled" else "error"}")
                viewModelScope.launch(Dispatchers.IO) { t.local.delete() }   // partial download or upload cache copy
                _state.update {
                    it.copy(transfer = null, fileStatus = if (ev.cancelled) "Transfer of ${t.name} cancelled." else "Transfer of ${t.name} failed: ${ev.message}")
                }
            }
            FileEvent.Ignored -> {}
        }
    }

    private fun refreshListing() { openRemoteFiles(_state.value.remotePath) }

    /** "" = PC home, "/" = the PC's drives, otherwise an absolute folder from a listing. */
    fun openRemoteFiles(path: String = "") {
        val requestId = ++fileListRequestId
        val v = viewer
        if (v == null) {
            _state.update { it.copy(fileStatus = "File browser unavailable: session is not active.") }
            return
        }
        _state.update { it.copy(fileStatus = loadingText) }
        v.readRemoteDir(path)
        viewModelScope.launch {
            delay(30_000)   // the first request also opens the file connection to the PC
            if (requestId == fileListRequestId && _state.value.fileStatus == loadingText) {
                _state.update { it.copy(fileStatus = "No response from the PC file browser. Tap Refresh to retry.") }
                ViewerLog.w("remote file listing timed out")
            }
        }
    }

    fun openRemoteEntry(entry: RemoteFileEntry) {
        if (!(entry.isDirectory || entry.isDrive)) return
        openRemoteFiles(RemoteFiles.child(_state.value.remotePath, entry))
    }

    fun remoteFilesUp() = openRemoteFiles(RemoteFiles.parent(_state.value.remotePath))

    fun remoteDrives() = openRemoteFiles("/")

    private fun busyWithTransfer(): Boolean {
        if (_state.value.transfer == null && transfers.isEmpty()) return false
        _state.update { it.copy(fileStatus = "Wait for the current transfer to finish, or cancel it.") }
        return true
    }

    fun uploadUri(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        if (busyWithTransfer()) return@launch
        val base = _state.value.remotePath
        if (base.isEmpty() || base == "/") {
            _state.update { it.copy(fileStatus = "Open a folder on the PC first, then upload into it.") }
            return@launch
        }
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        var name = "upload-${System.currentTimeMillis()}"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)?.takeIf { it.isNotBlank() } ?: name
        }
        val remoteName = RemoteFiles.safeName(name, _state.value.remoteFiles.map { it.name }.toSet())
        val local = File(app.cacheDir, "nestra-upload-${System.currentTimeMillis()}")
        _state.update { it.copy(fileStatus = "Preparing $remoteName…") }
        val copied = try {
            resolver.openInputStream(uri)?.use { input -> local.outputStream().use { input.copyTo(it) } } != null
        } catch (e: java.io.IOException) { false }
        if (!copied) {
            local.delete()
            _state.update { it.copy(fileStatus = "Could not read the selected file.") }
            return@launch
        }
        val remote = if (base.endsWith("\\") || base.endsWith("/")) base + remoteName else "$base\\$remoteName"
        val id = viewer?.transferFile(local.absolutePath, remote, false) ?: -1
        if (id >= 0) {
            transfers[id] = PendingTransfer(remoteName, true, local)
            _state.update { it.copy(transfer = FileTransferUi(id, remoteName, true, 0, "Uploading $remoteName…"), fileStatus = null) }
        } else {
            local.delete()
            _state.update { it.copy(fileStatus = it.fileStatus?.takeIf { s -> s.startsWith("Transfer could not start") } ?: "Upload could not start.") }
        }
    }

    fun downloadRemoteFile(entry: RemoteFileEntry) {
        if (!entry.isFile || busyWithTransfer()) return
        val app = getApplication<Application>()
        val base = _state.value.remotePath
        val remote = if (base.endsWith("\\") || base.endsWith("/")) base + entry.name else "$base\\${entry.name}"
        // received into this app's own folder first (the only place the native core writes), then exported
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "incoming").apply { mkdirs() }
        val localName = RemoteFiles.safeName(entry.name)
        val local = File(dir, "nestra-download-${System.currentTimeMillis()}")
        val id = viewer?.transferFile(remote, local.absolutePath, true) ?: -1
        if (id >= 0) {
            transfers[id] = PendingTransfer(localName, false, local)
            _state.update { it.copy(transfer = FileTransferUi(id, localName, false, 0, "Downloading $localName…"), fileStatus = null) }
        } else {
            _state.update { it.copy(fileStatus = it.fileStatus?.takeIf { s -> s.startsWith("Transfer could not start") } ?: "Download could not start.") }
        }
    }

    fun cancelTransfer() {
        val t = _state.value.transfer ?: return
        viewer?.cancelFileJob(t.id)
        _state.update { it.copy(fileStatus = "Cancelling…") }
    }

    /**
     * Android 10+: copies a finished download to the public Download/NESTRA folder (MediaStore, no storage permission)
     * and removes the private copy. Returns where it went, or null (the file then stays in the app folder).
     */
    private fun exportToDownloads(src: File, name: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NESTRA")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: throw java.io.IOException("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val shown = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: name
            src.delete()
            "Download/NESTRA/$shown"
        } catch (e: Exception) {
            ViewerLog.w("download export failed: ${e.javaClass.simpleName}")
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Disconnect on the phone: the DISCONNECT button or Back on the session screen ([source] is logged). */
    fun disconnectSession(source: String) {
        reconnectCancelled = true
        ViewerLog.w("disconnectSession($source) from ${ViewerLog.caller()}")
        controller?.disconnect(source)
    }

    private fun endText(reason: String) = when (reason) {
        "viewer_disconnect" -> "Session ended."
        "local_disconnect" -> "The session was ended on the PC (DISCONNECT)."
        "remote_access_off" -> "Remote Access was switched OFF on the PC. The session ended."
        "device_unpaired", "device_revoked" -> "This PC is no longer available to your account."
        "account_signed_out" -> "You were signed out. The session ended."
        "engine_exited", "engine_failed", "viewer_failed", "engine_closed" -> "The remote desktop connection closed."
        "connection_error" -> "Could not connect to the PC screen (connection error). Try again."
        "login_failed" -> "The PC did not accept the one-time session key. Try again."
        "insecure_connection" -> "The connection to the PC could not be end-to-end encrypted, so it was not opened."
        "expired" -> "The PC did not answer in time."
        else -> "Session ended."
    }

    /** Only when the Activity really finishes (not on rotation / configuration change: the ViewModel survives those). */
    override fun onCleared() {
        reconnectCancelled = true
        if (controller != null) ViewerLog.w("ViewModel cleared (app closed) -> disconnect")
        controller?.disconnect("app-closed")
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ internals
    private suspend fun signedIn(accountId: Long) {
        _state.update { it.copy(accountId = accountId) }
        loadDevices()
        val pending = _state.value.pairingId
        if (pending != null) openPair(pending) else go(Screen.Devices)
    }

    private suspend fun loadDevices() {
        when (val r = io { session.devices() }) {
            is ApiResult.Ok -> _state.update { it.copy(devices = r.value, devicesLoaded = true) }
            else -> handle(r)
        }
    }

    private fun handle(r: ApiResult<*>) {
        if (session.state is RemoteSession.State.SignedOut) {
            _state.value = UiState(screen = Screen.SignIn, message = "Your NESTRA session has ended. Please sign in again.")
            return
        }
        say(when (r) {
            ApiResult.InvalidOrExpired -> "That code is wrong or has expired. Start pairing again on your PC for a new code."
            ApiResult.RateLimited -> "Too many attempts. Wait a few minutes and try again."
            ApiResult.NotFound -> "This device is not available in your account."
            ApiResult.Unauthorized -> "Please sign in again."
            ApiResult.NetworkError, ApiResult.ServiceUnavailable -> "NESTRA Remote is not reachable. Check your connection."
            else -> "Something went wrong. Please try again."
        })
    }

    private fun fail(f: RemoteSession.Failure) = say(when (f) {
        RemoteSession.Failure.INVALID_CREDENTIALS -> "E-mail or password is not correct."
        RemoteSession.Failure.MFA_CODE_MALFORMED -> "Enter the 6-digit code from your authenticator app."
        RemoteSession.Failure.MFA_REJECTED -> "That code was not accepted. Use the current code from your authenticator."
        RemoteSession.Failure.RATE_LIMITED -> "Too many attempts. Wait a few minutes."
        RemoteSession.Failure.NETWORK, RemoteSession.Failure.SERVICE_UNAVAILABLE -> "NESTRA is not reachable. Check your connection."
        RemoteSession.Failure.SUBSCRIPTION_EXPIRED -> "Your NESTRA subscription has expired."
        RemoteSession.Failure.ACCOUNT_NOT_ACTIVE -> "This NESTRA account is not active."
        RemoteSession.Failure.NOT_ESTABLISHED, RemoteSession.Failure.ACCOUNT_MISMATCH,
        RemoteSession.Failure.REMOTE_REFUSED, RemoteSession.Failure.UNEXPECTED -> "Sign-in could not be completed. Please try again."
    })

    private fun go(s: Screen, keepMessage: Boolean = false) = _state.update { it.copy(screen = s, message = if (keepMessage) it.message else null) }
    private fun say(m: String) = _state.update { it.copy(message = m) }
    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try { block() } finally { _state.update { it.copy(busy = false) } }
        }
    }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
