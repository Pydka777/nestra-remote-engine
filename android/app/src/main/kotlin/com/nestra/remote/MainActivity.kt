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

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // codes, account data and device lists are not for screenshots, screen recording or the recents thumbnail
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        handle(intent)
        setContent { NestraRemoteTheme { AppRoot(vm) } }
    }

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
