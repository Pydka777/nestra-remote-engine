package com.nestra.remote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** NESTRA Remote: dark first - charcoal, white text, orange pairing/action accent, clear green for online. */
object NrColors {
    val Background = Color(0xFF15171A)
    val Surface = Color(0xFF1E2126)
    val SurfaceHigh = Color(0xFF272B31)
    val Text = Color(0xFFF4F5F7)
    val Muted = Color(0xFF9AA1AB)
    val Accent = Color(0xFFFF8A3D)       // pairing / primary actions
    val Online = Color(0xFF34C759)
    val Offline = Color(0xFF6B7280)
    val Danger = Color(0xFFFF5C5C)
}

private val scheme = darkColorScheme(
    primary = NrColors.Accent, onPrimary = Color(0xFF1A1006),
    secondary = NrColors.Online, onSecondary = Color.Black,
    background = NrColors.Background, onBackground = NrColors.Text,
    surface = NrColors.Surface, onSurface = NrColors.Text,
    surfaceVariant = NrColors.SurfaceHigh, onSurfaceVariant = NrColors.Muted,
    error = NrColors.Danger, onError = Color.Black,
)

@Composable
fun NestraRemoteTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
