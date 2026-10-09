package com.nestra.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.nestra.remote.BuildConfig
import com.nestra.remote.R
import com.nestra.remote.core.api.RemoteDevice
import com.nestra.remote.core.pairing.PairingInput
import java.time.Duration
import java.time.Instant

@Composable
fun AppRoot(vm: AppViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(NrColors.Background).safeDrawingPadding().imePadding()) {
        when (val screen = s.screen) {
            Screen.Splash -> Splash()
            Screen.SignIn -> SignIn(s, vm)
            Screen.Mfa -> Mfa(s, vm)
            Screen.Devices -> Devices(s, vm)
            Screen.Pair -> Pair(s, vm)
            is Screen.DeviceDetails -> DeviceDetails(s, vm, s.devices.firstOrNull { it.deviceId == screen.deviceId })
            Screen.Settings -> Settings(s, vm)
            is Screen.Session -> com.nestra.remote.viewer.ViewerScreen(s, vm, s.devices.firstOrNull { it.deviceId == screen.deviceId }?.name ?: "PC")
        }
        if (s.busy && s.screen != Screen.Splash) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(16.dp).size(22.dp), strokeWidth = 2.dp)
    }
}

// ---------------------------------------------------------------------------------------------- building blocks
@Composable private fun Header(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("Back") } else Spacer(Modifier.width(12.dp))
        Image(
            painter = painterResource(R.drawable.nestra_remote_icon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
        )
        Spacer(Modifier.width(9.dp))
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = NrColors.Text)
        action?.invoke()
    }
}

@Composable private fun Message(text: String?, onDismiss: () -> Unit) {
    if (text == null) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onDismiss),
        colors = CardDefaults.cardColors(containerColor = NrColors.SurfaceHigh)) {
        Text(text, Modifier.padding(14.dp), color = NrColors.Text, fontSize = 14.sp)
    }
}

@Composable private fun Brand(subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Image(
            painter = painterResource(R.drawable.nestra_remote_icon),
            contentDescription = "NESTRA Remote",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(104.dp).clip(RoundedCornerShape(24.dp))
        )
        Spacer(Modifier.height(12.dp))
        Text("NESTRA", color = NrColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
        Text("REMOTE", color = NrColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 8.sp)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = NrColors.Muted, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------------------------------------- splash / sign in / MFA
@Composable private fun Splash() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Brand("Checking your session...")
        Spacer(Modifier.height(24.dp)); CircularProgressIndicator()
    }
}

@Composable private fun SignIn(s: UiState, vm: AppViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }          // never saved across process death
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Brand("Sign in with your NESTRA account")
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("E-mail") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Spacer(Modifier.height(20.dp))
        Button(onClick = { val p = password.toCharArray(); password = ""; vm.signIn(email, p) },
            enabled = !s.busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Sign in") }
        Spacer(Modifier.height(12.dp))
        Text("The same account as NESTRA Parent. NESTRA Remote has no separate password; your password goes only to NESTRA.",
            color = NrColors.Muted, fontSize = 12.sp)
    }
    Box(Modifier.fillMaxWidth()) { Message(s.message, vm::dismissMessage) }
}

@Composable private fun Mfa(s: UiState, vm: AppViewModel) {
    var code by remember { mutableStateOf("") }
    BackHandler { vm.cancelMfa() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Brand("Two-step verification")
        Spacer(Modifier.height(28.dp))
        Text("Enter the 6-digit code from your authenticator app.", color = NrColors.Text)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, Modifier.fillMaxWidth(), label = { Text("Code") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Spacer(Modifier.height(20.dp))
        Button(onClick = { val c = code; code = ""; vm.submitMfa(c) }, enabled = !s.busy && code.length == 6,
            modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Verify") }
        TextButton(onClick = vm::cancelMfa, modifier = Modifier.fillMaxWidth()) { Text("Back to sign in") }
    }
    Box(Modifier.fillMaxWidth()) { Message(s.message, vm::dismissMessage) }
}

// ---------------------------------------------------------------------------------------------- devices
@Composable private fun Devices(s: UiState, vm: AppViewModel) = Column(Modifier.fillMaxSize()) {
    Header("My devices", action = { TextButton(onClick = vm::openSettings) { Text("Settings") } })
    Message(s.message, vm::dismissMessage)
    if (s.devicesLoaded && s.devices.isEmpty()) {
        Column(Modifier.fillMaxWidth().weight(1f).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No devices connected", color = NrColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Pair your Windows PC: on the PC open NESTRA Remote, choose \"Pair this PC\" and enter the code here.",
                color = NrColors.Muted, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { vm.openPair() }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Pair your Windows PC") }
        }
    } else {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(s.devices, key = { it.deviceId }) { d -> DeviceCard(d) { vm.openDevice(d.deviceId) } }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::refreshDevices, enabled = !s.busy, modifier = Modifier.weight(1f)) { Text("Refresh") }
            Button(onClick = { vm.openPair() }, modifier = Modifier.weight(1f)) { Text("Pair a PC") }
        }
    }
}

@Composable private fun Dot(online: Boolean) =
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) NrColors.Online else NrColors.Offline))

@Composable private fun Badge(text: String, color: Color) =
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)

@Composable private fun DeviceCard(d: RemoteDevice, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = NrColors.Surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(d.online); Spacer(Modifier.width(10.dp))
                Text(d.name, Modifier.weight(1f), color = NrColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (d.online) "Online" else "Offline", color = if (d.online) NrColors.Online else NrColors.Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("${d.platform}${d.model?.let { " · $it" } ?: ""} · last seen ${ago(d.lastSeenUtc)}", color = NrColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(if (d.paired) "Paired" else "Not paired", if (d.paired) NrColors.Online else NrColors.Muted)
                Badge("Remote Access ${if (d.remoteEnabled) "ON" else "OFF"}", if (d.remoteEnabled) NrColors.Accent else NrColors.Muted)
                if (d.revoked) Badge("Revoked", NrColors.Danger)
            }
        }
    }
}

private fun ago(iso: String?): String = try {
    if (iso == null) "never" else {
        val m = Duration.between(Instant.parse(iso), Instant.now()).toMinutes()
        when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
    }
} catch (e: Exception) { "unknown" }

@Composable private fun DeviceDetails(s: UiState, vm: AppViewModel, d: RemoteDevice?) = Column(Modifier.fillMaxSize()) {
    BackHandler { vm.back() }
    Header(d?.name ?: "Device", onBack = vm::back)
    Message(s.message, vm::dismissMessage)
    if (d == null) { Text("This device is no longer in your account.", Modifier.padding(16.dp), color = NrColors.Muted); return@Column }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Dot(d.online); Spacer(Modifier.width(8.dp)); Text(if (d.online) "Online" else "Offline · last seen ${ago(d.lastSeenUtc)}", color = NrColors.Text) }
        Detail("Platform", d.platform + (d.model?.let { " · $it" } ?: ""))
        Detail("Pairing status", if (d.paired) "Paired with your account" else "Not paired")
        Detail("Remote Access", if (d.remoteEnabled) "ON (turned on at the PC)" else "OFF - only the person at the PC can turn it on")
        Detail("Device ID", d.deviceId)
        Spacer(Modifier.height(8.dp))
        // ETAP 9: Connect only when the owner switched Remote Access ON at the PC; the phone can never switch it on
        Button(onClick = { vm.connect(d.deviceId) }, enabled = d.canConnect && d.engineReady && d.liveSession == null && !s.busy,
            modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Connect") }
        when {
            !d.remoteEnabled -> Text(com.nestra.remote.core.session.LiveSessionText.REMOTE_ACCESS_DISABLED, color = NrColors.Muted)
            !d.online -> Text("This PC is offline.", color = NrColors.Muted)
            !d.engineReady -> Text("The remote desktop engine on this PC is not ready. Update NESTRA Remote on the PC.", color = NrColors.Muted)
            d.liveSession != null -> Text("A remote session to this PC is in progress.", color = NrColors.Muted)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { confirm = true }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) { Text("Remove device", color = NrColors.Danger) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Remove ${d.name}?") },
        text = { Text("The PC is unpaired from your account and Remote Access is turned OFF. Its identity stays; it can be paired again with a new code.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.unpair(d.deviceId) }) { Text("Remove", color = NrColors.Danger) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable private fun Detail(label: String, value: String) = Column {
    Text(label, color = NrColors.Muted, fontSize = 12.sp); Text(value, color = NrColors.Text, fontSize = 15.sp)
}

// ---------------------------------------------------------------------------------------------- pairing
@Composable private fun Pair(s: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    BackHandler { vm.back() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Pair your Windows PC", onBack = vm::back)
        Message(s.message, vm::dismissMessage)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.pairedDeviceId != null) {
                Text("Paired.", color = NrColors.Online, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Remote Access stays OFF. Nothing on the PC changes until its owner turns Remote Access on there.", color = NrColors.Muted)
                Button(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text("My devices") }
                return@Column
            }
            Text("1. On the PC, open NESTRA Remote and choose \"Pair this PC\" (Windows asks for administrator approval).", color = NrColors.Text)
            Text("2. Scan the QR code on the PC (optional) and type the 6-digit code it shows. The code is valid for 5 minutes and works once.", color = NrColors.Text)
            if (s.pairingId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge("PC selected from QR", NrColors.Accent)
                    TextButton(onClick = vm::clearPairingId) { Text("Clear") }
                }
            } else {
                OutlinedButton(onClick = {
                    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(ctx, opts).startScan()
                        .addOnSuccessListener { b -> b.rawValue?.let { raw -> PairingInput.pairingIdFromQr(raw) }?.let { id -> vm.pairingLink(id) } }
                }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR on the PC") }
            }
            OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, Modifier.fillMaxWidth(), label = { Text("6-digit code from the PC") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            val p = s.preview
            if (p == null) {
                Button(onClick = { vm.preview(code) }, enabled = !s.busy && code.length == 6, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Continue") }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = NrColors.Surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Pair this PC with your account?", color = NrColors.Muted, fontSize = 13.sp)
                        Text(p.deviceName, color = NrColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        p.model?.let { Text(it, color = NrColors.Muted) }
                        Spacer(Modifier.height(6.dp))
                        Text("Remote Access will stay OFF after pairing.", color = NrColors.Muted, fontSize = 13.sp)
                    }
                }
                Button(onClick = { val c = code; code = ""; vm.completePairing(c) }, enabled = !s.busy && code.length == 6,
                    modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Pair ${p.deviceName}") }
                TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- settings
@Composable private fun Settings(s: UiState, vm: AppViewModel) = Column(Modifier.fillMaxSize()) {
    BackHandler { vm.back() }
    var confirm by remember { mutableStateOf(false) }
    Header("Settings", onBack = vm::back)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Detail("Signed in", "NESTRA account ${s.accountId ?: "-"}")
        Detail("App", "NESTRA Remote Android ${BuildConfig.VERSION_NAME} (ETAP 10 all-in-one test build)")
        Detail("Licence", "NESTRA Remote for Android is free software under the GNU AGPL v3, based on RustDesk. No warranty. " +
            "Source code: ${BuildConfig.SOURCE_URL}")
        Detail("Security", "Your password and MFA codes are never stored. The NESTRA session is encrypted with this phone's Android Keystore; the Remote access token lives only in memory for 5 minutes.")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { confirm = true }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Sign out") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Sign out?") },
        text = { Text("The Remote access token is revoked, the NESTRA session is ended and this phone forgets it.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.logout() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
