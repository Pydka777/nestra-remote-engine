package com.nestra.remote

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.nestra.remote.core.pairing.PairingInput
import com.nestra.remote.ui.AppRoot
import com.nestra.remote.ui.AppViewModel
import com.nestra.remote.ui.NestraRemoteTheme
import com.nestra.remote.viewer.ViewerLog

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // codes, account data and device lists are not for screenshots, screen recording or the recents thumbnail
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handle(intent)
        setContent { NestraRemoteTheme { AppRoot(vm) } }
    }

    // v0.2.1 diagnostics: lifecycle changes never end a session (only DISCONNECT / Back / the server do); logged to
    // show that in logcat next to the session lines
    override fun onResume() { super.onResume(); ViewerLog.i("activity onResume") }
    override fun onPause() { ViewerLog.i("activity onPause"); super.onPause() }
    override fun onStop() { ViewerLog.i("activity onStop"); super.onStop() }
    override fun onDestroy() { ViewerLog.i("activity onDestroy finishing=$isFinishing configChange=$isChangingConfigurations"); super.onDestroy() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** nestra://pair/<PairingId> only pre-fills the pairing screen (the code is still typed by the person). */
    private fun handle(intent: Intent?) {
        val data = intent?.dataString ?: return
        PairingInput.pairingIdFromQr(data)?.let { vm.pairingLink(it) }
    }
}
