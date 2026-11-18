package com.smartpesa.student.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Shared tokens follow the supplied reference rather than Material's default palette.
val CanvasColor = Color(0xFF09090B)
val CardColor = Color(0xFF141416)
val Blue = Color(0xFF3981F7)
val Muted = Color(0xFF939399)
val Green = Color(0xFF00C896)
val Pink = Color(0xFFFF287A)
val Amber = Color(0xFFFFAB00)

@Composable
fun SmartPesaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = CanvasColor,
        surface = CardColor, onPrimary = Color.White, onSurface = Color.White, onBackground = Color.White,
        secondary = Green, error = Pink), content = content)
}
